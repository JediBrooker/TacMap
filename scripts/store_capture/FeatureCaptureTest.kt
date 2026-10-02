package com.tacmap.map

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.settings.OpsecSettings
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Screen capture only, from the pinned 3.0 production activity and controls. */
@RunWith(AndroidJUnit4::class)
class FeatureCaptureTest {
    private val instr=InstrumentationRegistry.getInstrumentation()
    private val context=instr.targetContext
    private val device=UiDevice.getInstance(instr)
    private lateinit var vm:MapViewModel
    private var scenario:ActivityScenario<MainActivity>?=null
    private val dir=File(context.filesDir,"store-capture").apply { mkdirs() }
    private fun waitFor(text:String,timeout:Long=15000)=requireNotNull(device.wait(Until.findObject(By.text(text)),timeout)) { "Missing $text" }
    private fun click(text:String) {
        val exact=device.wait(Until.findObject(By.text(text)),1500)
        val obj=exact ?: device.wait(Until.findObject(By.desc(text)),1500) ?: device.wait(Until.findObject(By.textContains(text)),10000)
        requireNotNull(obj) { "Missing control $text" }.click();Thread.sleep(1000)
    }
    private fun menu(text:String) { click("Menu");click(text) }
    private fun back() { device.pressBack();Thread.sleep(1000) }
    private fun capture(name:String,hold:Long=6000) {
        Thread.sleep(1200);device.waitForIdle();val start=System.currentTimeMillis()/1000.0
        check(device.takeScreenshot(File(dir,"$name.png")));Thread.sleep(hold)
        File(dir,"marks.jsonl").appendText("{\"name\":\"$name\",\"start\":$start,\"end\":${System.currentTimeMillis()/1000.0}}\n")
    }
    private fun launch(pdf:String?=null,online:Boolean=false) {
        scenario?.close();Thread.sleep(1000)
        val prefs=OpsecSettings.shared ?: OpsecSettings(context);prefs.setOnlineBasemaps(false);prefs.setOnlineLookups(online)
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_GRID","1").putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS","1")
            .putExtra("TACMAP_DEBUG_CAMERA","37.786,-122.438,14,0")
        if(pdf!=null) {
            val f=File(context.filesDir,"store-fixtures/$pdf").apply{parentFile!!.mkdirs()}
            instr.context.assets.open("store/$pdf").use{input->f.outputStream().use{input.copyTo(it)}}
            intent.putExtra("TACMAP_DEBUG_IMPORT_PDF",f.absolutePath)
        }
        scenario=ActivityScenario.launch(intent);scenario!!.onActivity{vm=ViewModelProvider(it)[MapViewModel::class.java]}
        Thread.sleep(6000)
        val retry=device.findObject(By.text("Retry"));if(retry!=null) { retry.click();Thread.sleep(4000) }
    }
    private fun waitUntil(what:String,condition:()->Boolean) {
        val end=System.currentTimeMillis()+45000
        while(!condition()){check(System.currentTimeMillis()<end){"Timeout: $what"};Thread.sleep(100)}
    }
    private fun offline() {
        launch("GeoPDF_San_Francisco.pdf");capture("pdf-hero")
        menu("Import / Export");check(device.wait(Until.hasObject(By.textContains("PDF Map")),15000));capture("import-export");back()
        launch("San_Francisco_Field_Map.pdf");capture("calibration-datum");click("NAD83")
        waitUntil("calibration ready"){vm.calibration.state.value?.phase==CalibrationPhase.Placing}
        val fids=listOf(Triple(676.742,487.370,"10SEG 48000 80000"),Triple(1149.142,484.526,"10SEG 52000 80000"),Triple(1151.979,956.929,"10SEG 52000 84000"),Triple(679.583,959.768,"10SEG 48000 84000"))
        fids.forEachIndexed { i,f ->
            instr.runOnMainSync{
                val g=vm.calibration.state.value!!.display.georef
                val c=g.toWGS84(f.first,f.second)!!;vm.flyTo(c.latitude,c.longitude,15f)
            };Thread.sleep(1600)
            instr.runOnMainSync{check(vm.calibration.beginAdd(CalibrationCapture(PagePoint(f.first,f.second),true,2.0,false)))}
            val field=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000));field.text=f.third;Thread.sleep(600);device.pressBack();Thread.sleep(500)
            if(i==1)capture("calibration-entry")
            requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Save point|Save anyway"))),10000)).click()
            waitUntil("saved point"){vm.calibration.state.value?.state?.points?.size==i+1}
        }
        instr.runOnMainSync{vm.flyTo(37.786,-122.438,13f)};Thread.sleep(2000)
        capture("calibration-fit");click("Points (4)");capture("calibration-points");back();click("Finish")
        device.findObject(By.text("Finish anyway"))?.click();waitUntil("finished"){vm.calibration.state.value==null};capture("calibrated-map")
        menu("Layers and Labels")
        repeat(3){device.swipe(device.displayWidth/2,(device.displayHeight*0.78).toInt(),device.displayWidth/2,(device.displayHeight*0.35).toInt(),30);Thread.sleep(800)}
        capture("map-library");back()
    }
    private fun navigation() {
        launch("GeoPDF_San_Francisco.pdf",true);capture("navigation-hud")
        val compass=device.findObject(By.descContains("compass"));compass?.click();Thread.sleep(1000);capture("heading-up",4000);compass?.click()
        menu("Search");val field=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000));field.click();field.text="10SEG 48000 84000";device.pressBack();capture("search");back()
        menu("Measure");device.click((device.displayWidth*.3).toInt(),(device.displayHeight*.45).toInt());Thread.sleep(500);device.click((device.displayWidth*.65).toInt(),(device.displayHeight*.5).toInt());capture("measure-line",4000)
        val prof=device.findObject(By.descContains("profile")) ?: device.findObject(By.descContains("Profile")) ?: device.findObject(By.textContains("Profile"))
        requireNotNull(prof){"Missing profile"}.click();Thread.sleep(12000);capture("line-of-sight");back()
        device.click((device.displayWidth*.6).toInt(),(device.displayHeight*.68).toInt());capture("measure-area",4000);click("Done")
        menu("Start Track Recording");capture("recording");menu("Stop Track Recording");device.findObject(By.text("Done"))?.click()
        menu("Weather & UAV Safety");Thread.sleep(4000);capture("weather")
        device.swipe(device.displayWidth/2,(device.displayHeight*.75).toInt(),device.displayWidth/2,(device.displayHeight*.3).toInt(),30);capture("sun-moon");back()
        val moon=device.findObject(By.desc("Turn on night mode"));requireNotNull(moon).click();capture("night-mode");device.findObject(By.desc("Turn off night mode"))!!.click()
    }
    private fun field() {
        launch("GeoPDF_San_Francisco.pdf",true)
        menu("Start Track Recording");capture("recording");menu("Stop Track Recording");device.findObject(By.text("Done"))?.click()
        menu("Weather & UAV Safety");Thread.sleep(4000);capture("weather");device.swipe(device.displayWidth/2,(device.displayHeight*.75).toInt(),device.displayWidth/2,(device.displayHeight*.3).toInt(),30);capture("sun-moon");back()
        click("Turn on night mode");capture("night-mode");click("Turn off night mode")
    }
    private fun mission() {
        launch();menu("Symbology");capture("symbols-list",4000)
        val add=device.findObject(By.textContains("Military Unit")) ?: device.findObject(By.textContains("Add at Crosshair"))
        requireNotNull(add){"Missing military unit"}.click();capture("symbol-builder");back();back()
        menu("Drawings");capture("drawings");back();menu("Layers and Labels");capture("layers-labels");back()
        menu("Unit Sync");capture("unit-sync");back();menu("TacMap Chat");capture("chat");back()
        menu("Import / Export");capture("exports");device.swipe(device.displayWidth/2,(device.displayHeight*.8).toInt(),device.displayWidth/2,(device.displayHeight*.3).toInt(),30);capture("exports-formats",4000);back()
        menu("Settings, Privacy & OPSEC");capture("privacy");device.swipe(device.displayWidth/2,(device.displayHeight*.8).toInt(),device.displayWidth/2,(device.displayHeight*.3).toInt(),30);capture("display-language");back()
        menu("App Lock");capture("app-lock");back();menu("About & Credits");capture("about-tour");back()
    }
    private fun settings() {
        launch();menu("Settings, Privacy & OPSEC");capture("privacy");device.swipe(device.displayWidth/2,(device.displayHeight*.72).toInt(),device.displayWidth/2,(device.displayHeight*.35).toInt(),30);capture("display-language");back()
        menu("App Lock");capture("app-lock");back();menu("About & Credits");capture("about-tour");back()
    }
    private fun graphics() {
        launch("GeoPDF_San_Francisco.pdf",true);menu("Drawings");click("Line Tool");device.click((device.displayWidth*.25).toInt(),(device.displayHeight*.44).toInt());device.click((device.displayWidth*.5).toInt(),(device.displayHeight*.5).toInt());device.click((device.displayWidth*.7).toInt(),(device.displayHeight*.63).toInt());capture("drawing-route");click("Finish")
        device.swipe((device.displayWidth*.22).toInt(),(device.displayHeight*.65).toInt(),(device.displayWidth*.22).toInt(),(device.displayHeight*.65).toInt(),150);click("Range Rings Here");capture("range-rings-options");click("Add Rings");capture("range-rings");device.findObject(By.desc("Close drawing controls"))?.click();Thread.sleep(1000)
        menu("Layers and Labels");capture("basemap-options");repeat(5){if(device.findObject(By.desc("GeoPDF_San_Francisco"))==null)device.swipe(device.displayWidth/2,(device.displayHeight*.76).toInt(),device.displayWidth/2,(device.displayHeight*.36).toInt(),30)};click("GeoPDF_San_Francisco");click("Generate Offline Tiles");Thread.sleep(5000);capture("bake-options");back();back()
        launch("San_Francisco_Map_Book.pdf");capture("page-chooser");back()
    }
    private fun finishCapture() {
        launch("GeoPDF_San_Francisco.pdf",true);menu("Weather & UAV Safety");Thread.sleep(4000);capture("weather");device.swipe(device.displayWidth/2,(device.displayHeight*.75).toInt(),device.displayWidth/2,(device.displayHeight*.3).toInt(),30);capture("sun-moon");back()
        click("Turn on night mode");capture("night-mode");click("Turn off night mode")
        menu("Drawings");click("Line Tool");device.click((device.displayWidth*.25).toInt(),(device.displayHeight*.44).toInt());device.click((device.displayWidth*.5).toInt(),(device.displayHeight*.5).toInt());device.click((device.displayWidth*.7).toInt(),(device.displayHeight*.63).toInt());capture("drawing-route");click("Finish")
        device.swipe((device.displayWidth*.22).toInt(),(device.displayHeight*.65).toInt(),(device.displayWidth*.22).toInt(),(device.displayHeight*.65).toInt(),150);click("Range Rings Here");click("Add Rings");capture("range-rings")
    }
    private fun pages() {
        launch("San_Francisco_Map_Book.pdf",true);menu("Layers and Labels")
        repeat(6){if(device.findObject(By.desc("San_Francisco_Map_Book"))==null)device.swipe(device.displayWidth/2,(device.displayHeight*.76).toInt(),device.displayWidth/2,(device.displayHeight*.36).toInt(),30)}
        requireNotNull(device.findObject(By.desc("San_Francisco_Map_Book"))).click();click("Choose page");Thread.sleep(3000);capture("page-chooser");back()
    }
    private fun gps() {
        launch("GeoPDF_San_Francisco.pdf",true);menu("Start Track Recording");Thread.sleep(12000);check(vm.trackRecorder.uiState.value.showsRec){"Recording did not start"};capture("recording-gps");menu("Stop Track Recording");device.findObject(By.text("Done"))?.click()
    }
    private fun details() {
        launch("GeoPDF_San_Francisco.pdf",true);menu("Layers and Labels");capture("basemap-options")
        repeat(5){if(device.findObject(By.desc("GeoPDF_San_Francisco"))==null)device.swipe(device.displayWidth/2,(device.displayHeight*.76).toInt(),device.displayWidth/2,(device.displayHeight*.36).toInt(),30)}
        requireNotNull(device.findObject(By.desc("GeoPDF_San_Francisco"))).click();Thread.sleep(1000);device.dumpWindowHierarchy(File(dir,"bake-menu.xml"));capture("bake-menu",2000);click("Generate Offline Tiles");Thread.sleep(5000);capture("bake-options");back();back()
        launch("San_Francisco_Map_Book.pdf");capture("page-chooser");back()
        launch("GeoPDF_San_Francisco.pdf",true);menu("Drawings");click("Area");device.click((device.displayWidth*.25).toInt(),(device.displayHeight*.44).toInt());device.click((device.displayWidth*.5).toInt(),(device.displayHeight*.4).toInt());device.click((device.displayWidth*.7).toInt(),(device.displayHeight*.63).toInt());capture("drawing-area");click("Finish")
        menu("Drawings");click("Free Draw");device.swipe((device.displayWidth*.24).toInt(),(device.displayHeight*.64).toInt(),(device.displayWidth*.6).toInt(),(device.displayHeight*.52).toInt(),60);capture("drawing-free")
    }
    private fun team() {
        val prefs=OpsecSettings.shared ?: OpsecSettings(context);prefs.setRelayUrl("ws://127.0.0.1:8794")
        launch("GeoPDF_San_Francisco.pdf",true);menu("Unit Sync")
        device.findObject(By.text("Leave room"))?.click();Thread.sleep(1000)
        val fields=device.findObjects(By.clazz("android.widget.EditText"));check(fields.size>=2);fields[0].text="FIELD TEAM";fields[1].text=InstrumentationRegistry.getArguments().getString("teamCode")!!
        click("Join / create");device.findObject(By.text("Enable & Join"))?.click();Thread.sleep(10000);click("Done");menu("TacMap Chat");Thread.sleep(22000)
        val message=requireNotNull(device.findObject(By.clazz("android.widget.EditText")));message.click();message.text="Field team ready. Moving to rally point.";Thread.sleep(1000);device.pressBack();Thread.sleep(1500)
        device.dumpWindowHierarchy(File(dir,"chat-before.xml"));capture("chat-ready",2000)
        val send=requireNotNull(device.findObject(By.text("Send")));var sendButton=send
        while(!sendButton.isClickable && sendButton.parent!=null)sendButton=sendButton.parent
        waitUntil("chat Send ready"){sendButton.isEnabled};sendButton.click();Thread.sleep(1500);device.dumpWindowHierarchy(File(dir,"chat-confirm.xml"));capture("chat-confirm",2000)
        requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Send to [0-9]+ units?"))),10000)).click();Thread.sleep(4000);capture("chat-live");click("Done")
        menu("Unit Sync");repeat(3){device.swipe(device.displayWidth/2,(device.displayHeight*.72).toInt(),device.displayWidth/2,(device.displayHeight*.4).toInt(),30);Thread.sleep(800)};capture("sync-live");click("Done");capture("team-map");Thread.sleep(70000)
    }
    @Test fun captureFeatureChapter() {
        when(InstrumentationRegistry.getArguments().getString("chapter")?:"offline") { "offline"->offline();"navigation"->navigation();"mission"->mission();"field"->field();"team"->team();"settings"->settings();"graphics"->graphics();"details"->details();"gps"->gps();"pages"->pages();"finish"->finishCapture();else->error("Unknown capture chapter") }
    }
}
