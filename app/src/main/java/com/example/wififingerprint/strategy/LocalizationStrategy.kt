package com.example.wififingerprint.strategy

/**
 * Common Strategy interface for Wi-Fi Fingerprinting Localization algorithms.
 */
interface LocalizationStrategy {
    val name: String
    val shortCode: String

    /**
     * Builds or refreshes the internal model using raw survey data.
     */
    fun buildModel(surveys: List<RawSurveySpot>)

    /**
     * Infers the user's location given a live query scan.
     */
    fun infer(query: LiveQueryScan): LocalizationResult
}
