package dev.pi.demo.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.http.okHttpTransport
import dev.pi.agent.AgentEvent
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.AgentSession
import dev.pi.coding.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var apiKeyInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var output: TextView
    private var session: AgentSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        apiKeyInput = EditText(this).apply {
            hint = "DeepSeek API key（仅保存在应用私有目录）"
            setSingleLine(true)
        }
        promptInput = EditText(this).apply {
            hint = "输入任务，例如：列出当前目录"
            minLines = 2
        }
        val sendButton = Button(this).apply { text = "发送" }
        output = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(apiKeyInput)
        root.addView(promptInput)
        root.addView(sendButton)
        root.addView(
            ScrollView(this).apply {
                addView(output)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            },
        )
        setContentView(root)

        apiKeyInput.setText(
            CredentialStore(agentDir(), FileSystem.SYSTEM).readApiKey("deepseek").orEmpty(),
        )
        sendButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            val text = promptInput.text.toString().trim()
            if (key.isEmpty() || text.isEmpty()) {
                output.text = "需要 API key 和任务内容"
                return@setOnClickListener
            }
            sendButton.isEnabled = false
            output.text = ""
            scope.launch {
                try {
                    val active = session ?: run {
                        CredentialStore(agentDir(), FileSystem.SYSTEM).writeApiKey("deepseek", key)
                        createSession()
                    }
                    session = active
                    active.prompt(text)
                } catch (error: Exception) {
                    output.append("\n[error] ${error.message}")
                } finally {
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private suspend fun createSession(): AgentSession = createAgentSession(
        AgentSessionOptions(
            cwd = filesDir.absolutePath.toPath(),
            agentDir = agentDir(),
            transport = okHttpTransport(),
            model = "deepseek/deepseek-v4-flash",
        ),
    ).also { session ->
        session.subscribe { event ->
            when (event) {
                is SessionEvent.AgentEventReceived -> {
                    val agentEvent = event.event
                    if (agentEvent is AgentEvent.MessageUpdate) {
                        val update = agentEvent.assistantMessageEvent
                        if (update is TextDeltaEvent) output.append(update.delta)
                    }
                    if (agentEvent is AgentEvent.ToolExecutionStart) {
                        output.append("\n\n[tool ${agentEvent.toolName}] ${agentEvent.args}\n")
                    }
                }

                is SessionEvent.Settled -> output.append("\n\n[done]")
                else -> Unit
            }
        }
    }

    private fun agentDir() = (filesDir.absolutePath + "/agent").toPath()

    override fun onDestroy() {
        session?.abort()
        scope.cancel()
        super.onDestroy()
    }
}
