package dev.pi.coding.tools

import okio.FileSystem
import okio.Path

fun defaultCodingTools(
    cwd: Path,
    fs: FileSystem,
    mutationQueue: FileMutationQueue,
    shellExecutor: ShellExecutor? = null,
): List<CodingTool> = listOf(
    readTool(cwd, fs),
    bashTool(cwd, shellExecutor),
    editTool(cwd, fs, mutationQueue),
    writeTool(cwd, fs, mutationQueue),
    grepTool(cwd, fs),
    findTool(cwd, fs),
    lsTool(cwd, fs),
)
