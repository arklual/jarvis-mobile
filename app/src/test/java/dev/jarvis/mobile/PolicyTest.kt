package dev.jarvis.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика сети — договор с системой, а не деталь сборки.
 *
 * Она уже стоила приложению всего: манифест не разрешал открытый текст, а
 * приложение ходит к узлу по `http://127.0.0.1:<порт>` — локальному концу
 * SSH-туннеля. Android 9+ такое запрещает по умолчанию, и КАЖДЫЙ запрос падал
 * с «CLEARTEXT communication to 127.0.0.1 not permitted by network security
 * policy». Ни логика, ни разбор конвертов этого не ловят: код правильный,
 * запрещает его система.
 *
 * Поэтому проверка читает сами файлы сборки. Заодно она сторожит вторую
 * половину договора: разрешение обязано остаться УЗКИМ. Открытый текст к
 * любому хосту в приложении, у которого единственный транспорт наружу — твой
 * SSH, был бы прямым обманом.
 */
class PolicyTest {

    /** Файл модуля: тесты Gradle стартуют из каталога `app`, но не всегда. */
    private fun moduleFile(relative: String): File {
        val here = File(relative)
        return if (here.exists()) here else File("app/$relative")
    }

    private val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
    private val policy = moduleFile("src/main/res/xml/network_security_config.xml").readText()

    @Test
    fun `манифест ссылается на политику сети`() {
        assertTrue(
            "без ссылки политика не применяется, и запросы к туннелю запрещены системой",
            manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
    }

    @Test
    fun `открытый текст разрешён петле и только ей`() {
        assertTrue("нет исключения для 127.0.0.1", policy.contains("<domain includeSubdomains=\"false\">127.0.0.1</domain>"))
        assertTrue("нет исключения для localhost", policy.contains(">localhost</domain>"))
        assertTrue(
            "исключение должно быть в domain-config: base-config открыл бы открытый текст всем",
            policy.contains("<domain-config cleartextTrafficPermitted=\"true\">"),
        )
        assertTrue(
            "остальному миру открытый текст должен быть запрещён явно",
            policy.contains("<base-config cleartextTrafficPermitted=\"false\" />"),
        )
    }

    @Test
    fun `глобального разрешения открытого текста нет`() {
        // usesCleartextTraffic="true" открыл бы http к ЛЮБОМУ хосту — включая
        // те, куда приложение ходить не должно вовсе.
        assertFalse(
            "в манифесте появилось глобальное разрешение открытого текста",
            manifest.contains("usesCleartextTraffic=\"true\""),
        )
    }
}
