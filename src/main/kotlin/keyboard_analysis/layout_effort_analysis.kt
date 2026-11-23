package keyboard_analysis

import java.io.File

fun calculateWeightedReachEffortSum(
    charEffortMap: Map<Char, Double>,
    corpus: CorpusData,
    verbose: Boolean = false,
    layoutName: String = "",
    datasetName: String = corpus.datasetName
): Double {
    if (verbose) println("Karakter-elérési terhelés (Char Reach Effort) elemzése folyamatban...")

    var weightedEffortSum = 0.0

    // Végigmegyünk a szóközös karakteradatokon
    // (Ideális esetben a szóköz effortja a hüvelykujjon közel van az 1.0-hoz)
    for ((charStr, percentage) in corpus.spaceincludedletters) {
        if (charStr.isNotEmpty()) {
            // A "[SPACE]" speciális stringet vagy a sima karaktert alakítjuk Char-rá
            val char = if (charStr == "[SPACE]") ' ' else charStr[0]

            val effort = charEffortMap[char]

            if (effort != null) {
                // A percentage egy Double érték 0.0 és 100.0 között (pl. 10.5%).
                // Ahhoz, hogy az effort a valóságnak megfeleljen, 100-zal osztjuk (0.105-ös szorzó)
                val probability = percentage / 100.0

                weightedEffortSum += effort * probability
            }
        }
    }

    if (verbose) {
        if (layoutName.isNotEmpty() && datasetName.isNotEmpty()) {
            val resultDir = File("analysis_results/$datasetName/$layoutName")
            resultDir.mkdirs()
            val outputFile = File(resultDir, "char_reach_effort_analyzed.txt")

            outputFile.bufferedWriter().use { writer ->
                writer.write("Átlagos súlyozott leütési terhelés (Char Reach Effort): %.4f".format(weightedEffortSum))
                writer.newLine()
            }
            saveRawMetric(datasetName, layoutName, "char_reach_effort", weightedEffortSum)
            println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Char Reach Effort: %.4f)".format(weightedEffortSum))
        } else {
            println("Átlagos súlyozott leütési terhelés (Char Reach Effort): ${String.format("%.4f", weightedEffortSum)}")
        }
    }

    return weightedEffortSum
}

fun baseGravityEffortAnalysis(
    charEffortMap: Map<Char, Double>,
    verbose: Boolean = false,
    layoutName: String = "",
    datasetName: String = ""
): Double {
    if (verbose) println("Base Gravity Effort (Karakterek alap fizikai terhelése) elemzése folyamatban...")

    val baseGravityEffort = charEffortMap.values.sum()

    if (verbose) {
        if (layoutName.isNotEmpty() && datasetName.isNotEmpty()) {
            val resultDir = File("analysis_results/$datasetName/$layoutName")
            resultDir.mkdirs()
            val outputFile = File(resultDir, "base_gravity_effort_analyzed.txt")

            outputFile.bufferedWriter().use { writer ->
                writer.write("Teljes Base Gravity Effort (Karakterek fizikai effort összege): %.4f".format(baseGravityEffort))
                writer.newLine()
                writer.write("Kiosztáson szereplő karakterek száma: ${charEffortMap.size}")
                writer.newLine()
            }
            saveRawMetric(datasetName, layoutName, "base_gravity_effort", baseGravityEffort)
            println("Sikeresen végrehajtva: ${outputFile.path} létrehozva. (Base Gravity Effort: %.4f)".format(baseGravityEffort))
        } else {
            println("Base Gravity Effort: ${String.format("%.4f", baseGravityEffort)}")
        }
    }

    return baseGravityEffort
}