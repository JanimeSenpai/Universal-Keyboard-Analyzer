package CorpusAnalyzer

import java.io.File

fun launchCompleteCorpusAnalysis() {
    println("--- TELJES KORPUSZ ELEMZÉS (CCA) ---")
    println("Kérem az elemzendő korpusz fájl nevét (a processable_texts mappából, pl. 'szoveg.txt'):")
    val filename = readln().trim()

    completeCorpusAnalysis(filename)
}

fun completeCorpusAnalysis(filename: String) {
    val file = File("processable_texts/$filename")
    if (!file.exists()) {
        println("Hiba: A korpusz fájl nem található ($file).")
        return
    }

    println("\n==================================================")
    println("KORPUSZ ELEMZÉS INDÍTÁSA: $filename")
    println("==================================================")

    println("\n--- 1/4: Karaktergyakoriság (Letter Frequency) ---")
    letterfrequency(filename)

    println("\n--- 2/4: Bigram gyakoriság (Bigram Analysis) ---")
    bigramanalysis(filename)

    println("\n--- 3/4: Trigram gyakoriság (Trigram Analysis) ---")
    trigramanalysis(filename)

    println("\n--- 4/4: Szóközös trigram gyakoriság (Space Included Trigram Analysis) ---")
    spaceIncludedTrigramAnalysis(filename)

    println("\n==================================================")
    println("MINDEN KORPUSZELEMZÉS SIKERESEN BEFEJEZŐDÖTT!")
    println("Az eredmények az 'analysis_results/$filename' mappában találhatók.")
    println("==================================================")
}