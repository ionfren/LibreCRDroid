package re.abbot.librecr.app.idrive

import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplication
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIAction
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIComponent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIEvent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIModel
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIState
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIProperty
import de.bmw.idrive.BMWRemoting
import io.bimmergestalt.idriveconnectkit.rhmi.deserialization.loadFromXML
import org.junit.Assert.*
import org.junit.Test
import re.abbot.librecr.app.data.BmwIDriveDisplayMode

class IDriveGlucoseViewTest {
    private class App : RHMIApplication() {
        override val models = mutableMapOf<Int, RHMIModel>()
        override val actions = mutableMapOf<Int, RHMIAction>()
        override val events = mutableMapOf<Int, RHMIEvent>()
        override val states = mutableMapOf<Int, RHMIState>()
        override val components = mutableMapOf<Int, RHMIComponent>()
        val values = mutableMapOf<Int, Any?>()
        val properties = mutableMapOf<Pair<Int, Int>, Any?>()
        val eventsTriggered = mutableListOf<Map<Any, Any?>>()
        override fun setModel(modelId: Int, value: Any?) {
            // Match the OEM list contract: one-column data requires a one-column layout first.
            if (!ignoreUpdates && value is BMWRemoting.RHMIDataTable && modelId == 3) {
                check(properties[12 to RHMIProperty.PropertyId.LIST_COLUMNWIDTH.id] == "*")
            }
            values[modelId] = value
        }
        override fun getModel(modelId: Int): Any? = values[modelId]
        override fun setProperty(componentId: Int, propertyId: Int, value: Any?) { properties[componentId to propertyId] = value }
        override fun getProperty(componentId: Int, propertyId: Int): Any? = properties[componentId to propertyId]
        override fun triggerHMIEvent(eventId: Int, args: Map<Any, Any?>) { eventsTriggered.add(args) }
    }

    // Synthetic layout, with the dynamic image/list combination used by the signed OEM layout.
    private val layout = """
        <pluginApps><pluginApp>
          <models><raDataModel id="1"/><raImageModel id="2"/><raListModel id="3"/><raIntModel id="4"/></models>
          <actions><raAction id="5"/></actions>
          <hmiStates><hmiState id="10" textModel="1"><components>
            <image id="11" model="2"/><list id="12" model="3" action="5">
              <properties><property id="6" value="57,100,*"/></properties>
            </list>
          </components></hmiState></hmiStates>
          <events><focusEvent id="20" targetModel="4"/></events>
        </pluginApp></pluginApps>
    """.trimIndent().toByteArray()

    @Test fun `hidden pages send no images and reopening always redraws the latest content`() {
        val app = App().apply { loadFromXML(layout) }
        val bytes = byteArrayOf(1, 2, 3)
        var renders = 0
        val view = IDriveGlucoseView(app, RHMIDimensions.create(mapOf("hmi.display-width" to "800")), "Glucose") { width, height ->
            assertTrue(width > 0 && height > 0)
            renders++
            bytes
        }
        view.refresh()
        assertEquals(0, renders)
        view.open()
        assertEquals(1, renders)
        assertArrayEquals(bytes, app.models[2]?.asRaImageModel()?.value)
        assertEquals(10, app.eventsTriggered.last()[0.toByte()])
        view.state.focusCallback?.onFocus(false)
        view.refresh()
        assertEquals(1, renders)
        view.state.focusCallback?.onFocus(true)
        assertEquals(2, renders)
    }

    @Test(expected = IllegalStateException::class)
    fun `incompatible layout fails instead of registering an empty screen`() {
        IDriveGlucoseView(App(), RHMIDimensions.create(emptyMap()), "Glucose") { _, _ -> null }
    }

    @Test fun `replaces inherited three column list layout before sending one column data`() {
        val app = App().apply { loadFromXML(layout) }
        IDriveGlucoseView(app, RHMIDimensions.create(emptyMap()), "Glucose") { _, _ -> null }
        assertEquals("*", app.properties[12 to RHMIProperty.PropertyId.LIST_COLUMNWIDTH.id])
        assertEquals(1, (app.values[3] as BMWRemoting.RHMIDataTable).numColumns)
    }

    @Test fun `wide display resizes both image and content on split screen events`() {
        val app = App().apply { loadFromXML(layout) }
        val sizes = mutableListOf<Pair<Int, Int>>()
        val view = IDriveGlucoseView(app, RHMIDimensions.create(mapOf("hmi.display-width" to "1280")), "Glucose") { w, h ->
            assertEquals(w, app.properties[11 to RHMIProperty.PropertyId.WIDTH.id])
            assertEquals(h, app.properties[11 to RHMIProperty.PropertyId.HEIGHT.id])
            sizes += w to h
            byteArrayOf(1)
        }
        view.open()
        assertEquals(743 to 480, sizes.last())
        view.onHmiEvent(7, mapOf(7.toByte() to false))
        assertEquals(1211 to 480, sizes.last())
        view.onHmiEvent(7, mapOf(7.toByte() to true))
        assertEquals(743 to 480, sizes.last())
        view.onHmiEvent(7, mapOf(7.toByte() to true))
        view.onHmiEvent(7, mapOf(7.toByte() to "invalid"))
        view.onHmiEvent(7, mapOf(7.toByte() to 900))
        view.onHmiEvent(1, mapOf(7.toByte() to false))
        assertEquals(3, sizes.size)
        assertEquals(1, app.eventsTriggered.size)
    }

    @Test fun `mode changes while hidden are retained without image transfers`() {
        val app = App().apply { loadFromXML(layout) }
        val sizes = mutableListOf<Int>()
        val view = IDriveGlucoseView(app, RHMIDimensions.create(mapOf("hmi.display-width" to "1280")), "Glucose") { w, _ ->
            sizes += w
            byteArrayOf(1)
        }
        view.onHmiEvent(7, mapOf(7 to 0))
        assertTrue(sizes.isEmpty())
        view.open()
        assertEquals(listOf(1211), sizes)
        view.state.onHmiEvent(1, mapOf(4.toByte() to false))
        view.onHmiEvent(7, mapOf(7 to 1))
        assertEquals(1, sizes.size)
        view.state.onHmiEvent(1, mapOf(4.toByte() to true))
        assertEquals(listOf(1211, 743), sizes)
    }

    @Test fun `manual modes override car events and auto resumes the latest reported mode`() {
        val app = App().apply { loadFromXML(layout) }
        var mode = BmwIDriveDisplayMode.FULL_SCREEN
        val sizes = mutableListOf<Int>()
        val view = IDriveGlucoseView(app, RHMIDimensions.create(mapOf("hmi.display-width" to "1280")), "Glucose",
            displayMode = { mode }) { w, _ -> sizes.add(w); byteArrayOf(1) }
        view.open()
        assertEquals(1211, sizes.last())
        view.onHmiEvent(7, mapOf(7.toByte() to true))
        assertEquals(1, sizes.size)
        mode = BmwIDriveDisplayMode.AUTO
        view.refresh()
        assertEquals(743, sizes.last())
        mode = BmwIDriveDisplayMode.SPLIT_SCREEN
        view.onHmiEvent(7, mapOf(7.toByte() to false))
        view.refresh()
        assertEquals(743, sizes.last())
    }

    @Test fun `narrow physical screens keep their full viewport when split is reported`() {
        val app = App().apply { loadFromXML(layout) }
        var width = 0
        val view = IDriveGlucoseView(app, RHMIDimensions.create(mapOf("hmi.display-width" to "800")), "Glucose") { w, _ ->
            width = w
            byteArrayOf(1)
        }
        view.onHmiEvent(7, mapOf(7.toByte() to true))
        view.open()
        assertEquals(731, width)
    }

}
