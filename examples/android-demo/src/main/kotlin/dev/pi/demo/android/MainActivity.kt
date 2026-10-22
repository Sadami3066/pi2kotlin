package dev.pi.demo.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pi.agent.AgentEvent
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.Provider
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.api.openAICompletionsApi
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.auth.fileCredentialStore
import dev.pi.ai.http.okHttpTransport
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.AgentSession
import dev.pi.coding.session.SessionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

private const val TAG = "pi2kotlin-demo"

private const val MODEL_ONLINE = "deepseek/deepseek-v4-flash"

private const val MODEL_MOCK = "mock/mock"

private const val MOCK_BASE_URL = "http://127.0.0.1:8765/v1"

private const val OFFLINE_TEXT = "这是离线演示的流式输出。faux provider 不读取 API key，也不访问网络，" +
    "文本按 token 分块推送。如果主线程被阻塞，这段文字就不会逐块出现；" +
    "同时 StrictMode 会记录任何主线程网络或磁盘读写。"

private sealed interface UiEvent {
    data class Reset(val text: String) : UiEvent

    data class Append(val text: String) : UiEvent

    data class Status(val text: String) : UiEvent

    data class Busy(val value: Boolean) : UiEvent

    data object Finish : UiEvent
}

class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiChannel = Channel<UiEvent>(Channel.UNLIMITED)

    private lateinit var apiKeyInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var onlineButton: Button
    private lateinit var offlineButton: Button
    private lateinit var mockButton: Button
    private lateinit var abortButton: Button

    @Volatile
    private var session: AgentSession? = null

    @Volatile
    private var unsubscribe: (() -> Unit)? = null

    private var busy = false
    private var uiLogCount = 0
    private var savedKey = ""
    private var finishAfterRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableStrictMode()
        Log.i(
            TAG,
            "onCreate thread=${Thread.currentThread().name} isMain=${Looper.myLooper() == Looper.getMainLooper()} " +
                "device=${Build.MODEL} android=${Build.VERSION.RELEASE}",
        )
        buildUi()
        uiScope.launch {
            for (event in uiChannel) applyUi(event)
        }
        onlineButton.setOnClickListener { startOnline() }
        offlineButton.setOnClickListener { startOfflineDemo() }
        mockButton.setOnClickListener { startMockDemo() }
        abortButton.setOnClickListener {
            val active = session
            Log.i(TAG, "abort requested thread=${Thread.currentThread().name} hasSession=${active != null}")
            active?.abort()
            uiChannel.trySend(UiEvent.Status("已请求中止"))
        }
        uiChannel.trySend(UiEvent.Status("就绪 · 设备 ${Build.MODEL} / Android ${Build.VERSION.RELEASE} · 点『离线演示』无需 key"))
        loadSavedApiKey()
        finishAfterRun = intent?.getBooleanExtra("auto_finish", false) == true
        if (intent?.getBooleanExtra("auto_offline", false) == true) {
            window.decorView.post { startOfflineDemo() }
        }
        if (intent?.getBooleanExtra("auto_mock", false) == true) {
            window.decorView.post { startMockDemo() }
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
        Log.i(TAG, "StrictMode enabled on thread=${Thread.currentThread().name}")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        }
        apiKeyInput = EditText(this).apply {
            hint = "DeepSeek API key（仅保存在应用私有目录）"
            setSingleLine(true)
        }
        promptInput = EditText(this).apply {
            hint = "输入任务，例如：列出当前目录"
            minLines = 2
        }
        onlineButton = Button(this).apply {
            textSize = 13f
            text = "在线发送（DeepSeek）" }
        offlineButton = Button(this).apply {
            textSize = 13f
            text = "离线演示（faux，无需 key）" }
        mockButton = Button(this).apply {
            textSize = 13f
            text = "本地 mock（adb reverse）" }
        abortButton = Button(this).apply {
            textSize = 13f
            text = "中止" }
        val buttonsTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(onlineButton)
            addView(offlineButton)
        }
        val buttonsBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(mockButton)
            addView(abortButton)
        }
        output = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            addView(output)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(status)
        root.addView(apiKeyInput)
        root.addView(promptInput)
        root.addView(buttonsTop)
        root.addView(buttonsBottom)
        root.addView(scroll)
        setContentView(root)
    }

    private fun loadSavedApiKey() {
        workScope.launch {
            val key = try {
                fileCredentialStore(agentDir(), FileSystem.SYSTEM).readApiKey("deepseek").orEmpty()
            } catch (error: Exception) {
                Log.e(TAG, "read saved api key failed", error)
                ""
            }
            savedKey = key
            if (key.isNotEmpty()) {
                apiKeyInput.hint = "已保存 DeepSeek key（不回显，可留空）"
                uiChannel.trySend(UiEvent.Status("已检测到本机保存的 DeepSeek key（出于安全不回显）"))
            }
        }
    }

    private fun startOnline() {
        if (busy) return
        val key = apiKeyInput.text.toString().trim().ifEmpty { savedKey }
        val text = promptInput.text.toString().trim()
        if (key.isEmpty() || text.isEmpty()) {
            uiChannel.trySend(UiEvent.Status("在线模式需要 API key 和任务内容"))
            return
        }
        setBusy(true)
        uiChannel.trySend(UiEvent.Reset(""))
        uiChannel.trySend(UiEvent.Status("在线模式（DeepSeek）：准备中 ..."))
        Log.i(TAG, "online start thread=${Thread.currentThread().name}")
        workScope.launch {
            try {
                fileCredentialStore(agentDir(), FileSystem.SYSTEM).writeApiKey("deepseek", key)
                closeSession()
                Log.i(TAG, "online createAgentSession start thread=${Thread.currentThread().name}")
                val created = createAgentSession(
                    AgentSessionOptions(
                        cwd = filesDir.absolutePath.toPath(),
                        agentDir = agentDir(),
                        transport = okHttpTransport(),
                        model = MODEL_ONLINE,
                    ),
                )
                Log.i(TAG, "online createAgentSession done thread=${Thread.currentThread().name}")
                attach(created, "online")
                uiChannel.trySend(UiEvent.Status("在线模式（DeepSeek）：流式输出中"))
                created.prompt(text)
                uiChannel.trySend(
                    UiEvent.Status("在线模式完成 · 消息数=${created.messages.size} · 文件=${created.sessionFile?.name ?: "n/a"}"),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "online run failed", error)
                uiChannel.trySend(UiEvent.Append("\n[error] ${error.message}"))
                uiChannel.trySend(UiEvent.Status("在线模式失败：${error.message}"))
            } finally {
                uiChannel.trySend(UiEvent.Busy(false))
                requestFinishIfNeeded()
            }
        }
    }

    private fun startOfflineDemo() {
        if (busy) return
        setBusy(true)
        uiChannel.trySend(UiEvent.Reset("=== 离线演示（faux provider，无网络、无 API key）===\n\n"))
        uiChannel.trySend(UiEvent.Status("离线演示（faux provider）：准备中 ..."))
        Log.i(TAG, "offline start thread=${Thread.currentThread().name}")
        workScope.launch {
            try {
                closeSession()
                val faux = fauxProvider(
                    FauxProviderOptions(
                        tokenSize = FauxTokenSize(1, 1),
                        tokensPerSecond = 10.0,
                    ),
                )
                faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage(OFFLINE_TEXT))))
                Log.i(TAG, "offline createAgentSession start thread=${Thread.currentThread().name}")
                val created = createAgentSession(
                    AgentSessionOptions(
                        cwd = filesDir.absolutePath.toPath(),
                        agentDir = agentDir(),
                        transport = okHttpTransport(),
                        model = "faux/faux-1",
                        tools = emptyList(),
                        providers = listOf(faux.provider),
                    ),
                )
                Log.i(TAG, "offline createAgentSession done thread=${Thread.currentThread().name}")
                attach(created, "offline")
                uiChannel.trySend(UiEvent.Status("离线演示（faux provider）：流式输出中"))
                Log.i(TAG, "offline prompt start thread=${Thread.currentThread().name}")
                created.prompt("请开始离线演示")
                Log.i(
                    TAG,
                    "offline prompt done thread=${Thread.currentThread().name} " +
                        "chars=${created.getLastAssistantText()?.length}",
                )
                uiChannel.trySend(
                    UiEvent.Status("离线演示完成 · 消息数=${created.messages.size} · 主线程未阻塞（见 StrictMode/logcat）"),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "offline run failed", error)
                uiChannel.trySend(UiEvent.Append("\n[error] ${error.message}"))
                uiChannel.trySend(UiEvent.Status("离线演示失败：${error.message}"))
            } finally {
                uiChannel.trySend(UiEvent.Busy(false))
                requestFinishIfNeeded()
            }
        }
    }

    private fun startMockDemo() {
        if (busy) return
        setBusy(true)
        uiChannel.trySend(UiEvent.Reset("=== 本地 mock 服务器（adb reverse tcp:8765）===\n\n"))
        uiChannel.trySend(UiEvent.Status("本地 mock：准备中（需先运行 scripts/mock-sse-server.py 与 adb reverse）"))
        Log.i(TAG, "mock start thread=${Thread.currentThread().name}")
        workScope.launch {
            try {
                closeSession()
                val mockModel = Model(
                    id = "mock",
                    name = "mock",
                    api = "openai-completions",
                    provider = "mock",
                    baseUrl = MOCK_BASE_URL,
                    input = listOf(ModelInputModality.Text),
                    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                    reasoning = false,
                    contextWindow = 8192,
                    maxTokens = 2048,
                )
                val mockProvider = Provider(
                    id = "mock",
                    name = "mock",
                    baseUrl = MOCK_BASE_URL,
                    apiProviders = mapOf("openai-completions" to { openAICompletionsApi(okHttpTransport()) }),
                    models = listOf(mockModel),
                    auth = object : AuthResolver {
                        override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth =
                            ResolvedAuth(apiKey = "mock-key", source = "mock")
                    },
                )
                Log.i(TAG, "mock createAgentSession start thread=${Thread.currentThread().name}")
                val created = createAgentSession(
                    AgentSessionOptions(
                        cwd = filesDir.absolutePath.toPath(),
                        agentDir = agentDir(),
                        transport = okHttpTransport(),
                        model = MODEL_MOCK,
                        tools = emptyList(),
                        providers = listOf(mockProvider),
                    ),
                )
                Log.i(TAG, "mock createAgentSession done thread=${Thread.currentThread().name}")
                attach(created, "mock")
                uiChannel.trySend(UiEvent.Status("本地 mock：SSE 流式输出中（网络读取在 IO 线程）"))
                Log.i(TAG, "mock prompt start thread=${Thread.currentThread().name}")
                created.prompt("请用本地 mock 服务器演示流式输出")
                Log.i(
                    TAG,
                    "mock prompt done thread=${Thread.currentThread().name} chars=${created.getLastAssistantText()?.length}",
                )
                uiChannel.trySend(
                    UiEvent.Status("本地 mock 完成 · 消息数=${created.messages.size} · 网络读取在 IO 线程（见 StrictMode/logcat）"),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "mock run failed", error)
                uiChannel.trySend(UiEvent.Append("\n[error] ${error.message}"))
                uiChannel.trySend(UiEvent.Status("本地 mock 失败：${error.message}"))
            } finally {
                uiChannel.trySend(UiEvent.Busy(false))
                requestFinishIfNeeded()
            }
        }
    }

    private fun requestFinishIfNeeded() {
        if (!finishAfterRun) return
        finishAfterRun = false
        uiChannel.trySend(UiEvent.Finish)
    }

    private fun attach(created: AgentSession, mode: String) {
        session = created
        unsubscribe = created.subscribe { event ->
            when (event) {
                is SessionEvent.AgentEventReceived -> handleAgentEvent(event.event)
                is SessionEvent.Settled -> uiChannel.trySend(UiEvent.Append("\n\n[done]"))
                is SessionEvent.RetryScheduled -> uiChannel.trySend(
                    UiEvent.Status("重试 attempt=${event.attempt} delayMs=${event.delayMs} error=${event.errorMessage}"),
                )

                is SessionEvent.RetryExhausted -> uiChannel.trySend(
                    UiEvent.Status("重试耗尽 attempts=${event.attempts} error=${event.errorMessage}"),
                )

                is SessionEvent.SessionError -> uiChannel.trySend(UiEvent.Status("会话错误：${event.message}"))
                is SessionEvent.CompactionStarted -> uiChannel.trySend(UiEvent.Status("压缩开始 tokensBefore=${event.tokensBefore}"))
                is SessionEvent.CompactionFinished -> uiChannel.trySend(UiEvent.Status("压缩完成 tokensAfter=${event.tokensAfter}"))
                is SessionEvent.CompactionFailed -> uiChannel.trySend(UiEvent.Status("压缩失败：${event.errorMessage}"))
                else -> Unit
            }
        }
        Log.i(TAG, "session attached mode=$mode thread=${Thread.currentThread().name}")
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageUpdate -> {
                val update = event.assistantMessageEvent
                if (update is TextDeltaEvent) uiChannel.trySend(UiEvent.Append(update.delta))
            }

            is AgentEvent.ToolExecutionStart -> uiChannel.trySend(UiEvent.Append("\n[tool ${event.toolName}]\n"))
            is AgentEvent.MessageEnd -> {
                if (event.message.role == "assistant") uiChannel.trySend(UiEvent.Append("\n"))
            }

            else -> Unit
        }
    }

    private suspend fun closeSession() {
        val active = session
        session = null
        val listener = unsubscribe
        unsubscribe = null
        listener?.invoke()
        if (active != null) {
            try {
                active.close()
                Log.i(TAG, "session closed thread=${Thread.currentThread().name}")
            } catch (error: Exception) {
                Log.e(TAG, "session close failed", error)
            }
        }
    }

    private fun applyUi(event: UiEvent) {
        when (event) {
            is UiEvent.Reset -> {
                output.text = event.text
                scroll.post { scroll.scrollTo(0, 0) }
            }

            is UiEvent.Append -> {
                output.append(event.text)
                if (uiLogCount < 3) {
                    Log.i(
                        TAG,
                        "ui update thread=${Thread.currentThread().name} isMain=${Looper.myLooper() == Looper.getMainLooper()}",
                    )
                    uiLogCount += 1
                }
                scroll.post { scrollToBottom() }
            }

            is UiEvent.Status -> status.text = event.text
            is UiEvent.Busy -> setBusy(event.value)
            is UiEvent.Finish -> finish()
        }
    }

    private fun scrollToBottom() {
        val child = scroll.getChildAt(0) ?: return
        scroll.scrollTo(0, maxOf(0, child.height - scroll.height))
    }

    private fun setBusy(value: Boolean) {
        busy = value
        onlineButton.isEnabled = !value
        offlineButton.isEnabled = !value
        mockButton.isEnabled = !value
        abortButton.isEnabled = value
    }

    private fun agentDir(): Path = (filesDir.absolutePath + "/agent").toPath()

    override fun onDestroy() {
        Log.i(TAG, "onDestroy thread=${Thread.currentThread().name} hasSession=${session != null}")
        uiChannel.close()
        uiScope.cancel()
        workScope.cancel()
        val active = session
        val listener = unsubscribe
        session = null
        unsubscribe = null
        runBlocking(Dispatchers.IO) {
            try {
                listener?.invoke()
            } catch (error: Exception) {
                Log.e(TAG, "unsubscribe failed", error)
            }
            if (active != null) {
                withContext(NonCancellable) {
                    try {
                        active.close()
                        Log.i(TAG, "onDestroy closed session on thread=${Thread.currentThread().name}")
                    } catch (error: Exception) {
                        Log.e(TAG, "onDestroy session close failed", error)
                    }
                }
            }
        }
        Log.i(TAG, "onDestroy done thread=${Thread.currentThread().name}")
        super.onDestroy()
    }
}
