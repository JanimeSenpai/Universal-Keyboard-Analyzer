import CorpusAnalyzer.Locale
import CorpusAnalyzer.launchCalculateWordFrequency
import CorpusAnalyzer.launchCompleteCorpusAnalysis
import CorpusAnalyzer.launchSpaceIncludedTrigramAnalysis
import CorpusAnalyzer.launchbigramanalysis
import CorpusAnalyzer.launchbuildcorpora
import CorpusAnalyzer.launchtrigramanalysis
import CorpusAnalyzer.letterfrequency
import CorpusAnalyzer.preprocess
import keyboard_analysis.launch3RollAnalysis
import keyboard_analysis.launchAlternationAnalysis
import keyboard_analysis.launchCompleteLayoutAnalysis
import keyboard_analysis.launchGenerateSummaryCsv
import keyboard_analysis.launchHandBalanceAnalysis
import keyboard_analysis.launchPinkyOffAnalysis
import keyboard_analysis.launchRedirectAnalysis
import keyboard_analysis.launchRollAnalysis
import keyboard_analysis.launchRowUsageAnalysis
import keyboard_analysis.launchSimpleSfbAnalysis
import keyboard_analysis.launchSpaceSfs1Analysis
import keyboard_analysis.launchFullScissorAnalysis
import keyboard_analysis.launchHalfScissorAnalysis
import keyboard_analysis.launchLateralStretchAnalysis
import keyboard_analysis.launchPinkyStretchAnalysis
import keyboard_analysis.layoutSandbox
import gui.launchGui
import composegui.launchComposeGui
import kotlin.system.exitProcess

fun main() {
commandLineInterface()
}

val currentLocale: Locale = Locale.HU

fun commandLineInterface(){
    println("this is the command line interface of corpora analyzer")
    println("for available commands enter \"commands\" (or \"gui\" for Swing GUI, \"compose\" for Compose Multiplatform GUI)")
    readcommands()

}

fun readcommands(){
    val command  = readLine()

    if(command == null){
        println("the command should not be null")
    }else{
        when(command){
            "commands" -> listcommands()
            "gui" -> launchGui()
            "compose", "cgui" -> launchComposeGui()
            "CorpusAnalyzer.preprocess file"-> preprocess()
            "pf"-> preprocess()
            "letter frequency"->{
               launchletterfrequency()
            }
            "lf"->launchletterfrequency()
            "CorpusAnalyzer.buildcorpora"-> launchbuildcorpora()
            "bc" -> launchbuildcorpora()
            "bigram analysis" -> launchbigramanalysis()
            "ba" -> launchbigramanalysis()
            "keyboard bigram analysis" -> launchSimpleSfbAnalysis()
            "kba" -> launchSimpleSfbAnalysis()
            "trigram analysis" -> launchtrigramanalysis()
            "ta" -> launchtrigramanalysis()
            "layout alternation analysis" -> launchAlternationAnalysis()
            "laa" -> launchAlternationAnalysis()
            "layout 2roll analysis" -> launchRollAnalysis()
            "l2ra" -> launchRollAnalysis()
            "layout 3roll analysis" -> launch3RollAnalysis()
            "l3ra" -> launch3RollAnalysis()
            "layout redirect analysis" ->launchRedirectAnalysis()
            "lra"->launchRedirectAnalysis()
            "complete layout analysis" -> launchCompleteLayoutAnalysis()
            "cla" -> launchCompleteLayoutAnalysis()
            "complete corpus analysis" -> launchCompleteCorpusAnalysis()
            "cca" -> launchCompleteCorpusAnalysis()
            "sta" -> launchSpaceIncludedTrigramAnalysis()
            "calculate word frequency"-> launchCalculateWordFrequency()
            "sfs1" ->launchSpaceSfs1Analysis()
            "cwf"-> launchCalculateWordFrequency()
            "hb"-> launchHandBalanceAnalysis()
            "ru"->launchRowUsageAnalysis()//row usage
            "generate csv" -> launchGenerateSummaryCsv()
            "gcsv" -> launchGenerateSummaryCsv()
            "po" -> launchPinkyOffAnalysis()
            "pinky off" -> launchPinkyOffAnalysis()
            "lsb" -> launchLateralStretchAnalysis()
            "psb" -> launchPinkyStretchAnalysis()
            "fsb" -> launchFullScissorAnalysis()
            "hsb" -> launchHalfScissorAnalysis()
            "layoutsandbox" -> layoutSandbox()
            "exit"-> exitProcess(0)
        }

    }

    //utasítás végrehajtása után olvassa a következő utasítást
    readcommands()
}

fun listcommands(){
    println("\"gui\" -> launchGui()\n" +
            "\"compose\" / \"cgui\" -> launchComposeGui()\n" +
            "\"CorpusAnalyzer.preprocess file\"-> preprocess()\n" +
            "\"pf\"-> preprocess()\n" +
            "\"letter frequency\"->{\n  launchletterfrequency()\n" +
            "\"lf\"->launchletterfrequency()\n" +
            "\"CorpusAnalyzer.buildcorpora\"-> launchbuildcorpora()\n" +
            "\"bc\" -> launchbuildcorpora()\n" +
            "\"bigram analysis\" -> launchbigramanalysis()\n" +
            "\"ba\" -> launchbigramanalysis()\n" +
            "\"keyboard bigram analysis\" -> launchsimplesfbanalysis()\n" +
            "\"kba\" -> launchsimplesfbanalysis()\n" +
            "\"trigram analysis\" -> launchtrigramanalysis()\n" +
            "\"ta\" -> launchtrigramanalysis()\n" +
            "\"layout alternation analysis\" -> launchAlternationAnalysis()\n" +
            "\"laa\" -> launchAlternationAnalysis()\n" +
            "\"layout 2roll analysis\" -> launchRollAnalysis()\n" +
            "\"l2ra\" -> launchRollAnalysis()\n" +
            "\"layout 3roll analysis\" -> launch3RollAnalysis()\n" +
            "\"l3ra\" -> launch3RollAnalysis()\n" +
            "\"layout redirect analysis\" ->launchRedirectAnalysis()\n" +
            "\"lra\"->launchRedirectAnalysis()\n" +
            "\"complete layout analysis\" -> launchCompleteLayoutAnalysis()\n" +
            "\"cla\" -> launchCompleteLayoutAnalysis()\n" +
            "\"complete corpus analysis\" -> launchCompleteCorpusAnalysis()\n" +
            "\"cca\" -> launchCompleteCorpusAnalysis()\n" +
            "\"sta\" -> launchSpaceIncludedTrigramAnalysis()\n" +
            "\"calculate word frequency\"-> launchCalculateWordFrequency()\n" +
            "\"sfs1\" ->launchSpaceSfs1Analysis()\n" +
            "\"cwf\"-> launchCalculateWordFrequency()\n" +
            "\"hb\"-> launchHandBalanceAnalysis()\n" +
            "\"ru\"->launchRowUsageAnalysis()//row usage\n" +
            "\"generate csv\" -> launchGenerateSummaryCsv()\n" +
            "\"gcsv\" -> launchGenerateSummaryCsv()\n" +
            "\"po\" -> launchPinkyOffAnalysis()\n" +
            "\"pinky off\" -> launchPinkyOffAnalysis()\n" +
            "\"exit\"-> exitProcess(0)")
}


fun launchletterfrequency(){
    println("enter filename")
    val filename =readLine()
    if(filename==null){
        println("filename should not be null")
        return
    }
    letterfrequency(filename)
}
