package dev.jarvis.mobile

import dev.jarvis.mobile.model.Artifacts
import dev.jarvis.mobile.model.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {

    private fun tool(name: String, input: String) =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$name","input":$input}]}}"""

    @Test
    fun `файлы собираются по полному пути и сортируются по частоте`() {
        val jsonl = listOf(
            tool("Edit", """{"file_path":"/p/src/main.rs"}"""),
            tool("Read", """{"file_path":"/p/README.md"}"""),
            tool("Write", """{"file_path":"/p/src/main.rs"}"""),
            tool("Bash", """{"command":"cargo test"}"""),
        ).joinToString("\n")

        val arts = Artifacts.from(Transcript.parse(jsonl))
        assertEquals(2, arts.size)
        // путь целиком: узлу для открытия нужен именно он, а не имя из ленты
        assertEquals("/p/src/main.rs", arts[0].path)
        assertEquals(2, arts[0].touches)
        assertEquals("main.rs", arts[0].name)
        assertEquals("/p/src", arts[0].dir)
        // Bash — не файл, ему место в списке команд
        assertTrue(arts.none { it.path.contains("cargo") })
    }

    @Test
    fun `команды идут свежими сверху и без повторов`() {
        val jsonl = listOf(
            tool("Bash", """{"command":"cargo build"}"""),
            tool("Bash", """{"command":"cargo test"}"""),
            tool("Bash", """{"command":"cargo build"}"""),
        ).joinToString("\n")
        assertEquals(listOf("cargo build", "cargo test"), Artifacts.commands(Transcript.parse(jsonl)))
    }
}
