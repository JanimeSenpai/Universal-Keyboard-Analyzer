import EffortModel.*
import keyboard_analysis.CombinedLayoutData
import keyboard_analysis.LayoutParser
import java.io.File


fun layoutcharefforts(layout: CombinedLayoutData) {
    // A parser példányosítása (a hardvert és a betűket is a YAML-ből építi fel)
    // A fájl relatív útvonala a gyökérkönyvtárból indítva
    val yamlFile = File("keyboards/ltsnergomini.yaml")

    // Fájl beolvasása és az effort map legenerálása
    val effortMap = layout.buildCharEffortMap()

    // Eredmény kiíratása (terhelés szerint növekvő sorrendben)
    //println("Beolvasott billentyűzet karakter-terhelései:")  //ezt a továbbiakban nem akarom kiírni
    effortMap.entries.sortedBy { it.value }.forEach { (char, effort) ->
        // 3 tizedesjegyre kerekítve a könnyebb olvashatóságért
        println("$char : ${String.format("%.3f", effort)}")
    }
}