package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GleoTest {
    @Test fun 함수_이름이_의도로_바뀐다() {
        assertEquals(CarIntent.RECENT_CALLS, gleoIntent("READ_RECENT_CALLS"))
        assertEquals(CarIntent.NEXT_EVENT, gleoIntent("READ_NEXT_SCHEDULE"))
        assertEquals(CarIntent.URGENT, gleoIntent("ADD_REST_STOP"))
        assertEquals(CarIntent.URGENT, gleoIntent("ztw.carbiseo.ADD_REST_STOP"))
        assertNull(gleoIntent("OTHER"))
    }

    @Test fun 스키마_함수는_3개_이하이고_매니페스트와_맞다() {
        val names = Regex("\"name\": \"([A-Z_]+)\"").findAll(File("src/main/assets/schema.json").readText()).map { it.groupValues[1] }.toList()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(names.size in 1..3)
        names.forEach { assertNotNull(gleoIntent(it)); assertTrue(it, "android:name=\"$it\"" in manifest) }
    }
}
