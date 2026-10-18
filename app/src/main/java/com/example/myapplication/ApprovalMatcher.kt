package com.example.myapplication

import android.util.Log

object ApprovalMatcher {
    fun findOfficialApprovalNo(
        db: AppDatabase,
        manufacturer: String,
        itemType: String,
        formMaterial: String
    ): String {
        val normMan = normalize(manufacturer)
        val coreKeyword = extractCoreKeyword(normMan)
        val modelName = extractModelName(formMaterial)

        Log.d("ApprovalMatcher", "Matching -> Man: '$manufacturer' (norm: '$normMan', core: '$coreKeyword'), Item: '$itemType', Model: '$modelName', FormMat: '$formMaterial'")

        val approvals = kotlinx.coroutines.runBlocking {
            db.approvalDao().getAll()
        }

        if (approvals.isEmpty()) {
            return "형식승인 번호 매칭 데이터 없음 (DB 비어있음)"
        }

        val matchedNumbers = mutableListOf<String>()

        // 1순위: 제조업체(핵심 키워드)와 모델명이 모두 일치하는 경우 (가장 정확)
        if (modelName.isNotBlank()) {
            val cleanModel = modelName.replace(Regex("[-\\s]"), "").uppercase()
            for (app in approvals) {
                val manMatch = app.manufacturer.contains(coreKeyword, ignoreCase = true) || app.manufacturer.contains(normMan, ignoreCase = true)
                val cleanSpec = app.spec.replace(Regex("[-\\s]"), "").uppercase()
                val specMatch = cleanSpec.contains(cleanModel)
                if (manMatch && specMatch) {
                    val entry = "${app.approvalNo} (${app.certType})"
                    if (!matchedNumbers.contains(entry)) {
                        matchedNumbers.add(entry)
                    }
                }
            }
        }

        // 2순위: 제조업체 일치 + 품명(itemType) 일치
        if (matchedNumbers.isEmpty()) {
            val cleanItemType = itemType.replace(" ", "")
            for (app in approvals) {
                val manMatch = app.manufacturer.contains(coreKeyword, ignoreCase = true) || app.manufacturer.contains(normMan, ignoreCase = true)
                val cleanAppItem = app.itemName.replace(" ", "")
                val itemMatch = cleanAppItem.contains(cleanItemType, ignoreCase = true) || cleanItemType.contains(cleanAppItem, ignoreCase = true)
                if (manMatch && itemMatch) {
                    val entry = "${app.approvalNo} (${app.certType})"
                    if (!matchedNumbers.contains(entry)) {
                        matchedNumbers.add(entry)
                    }
                }
            }
        }

        // 3순위: 제조업체만 일치
        if (matchedNumbers.isEmpty()) {
            for (app in approvals) {
                val manMatch = app.manufacturer.contains(coreKeyword, ignoreCase = true) || app.manufacturer.contains(normMan, ignoreCase = true)
                if (manMatch) {
                    val entry = "${app.approvalNo} (${app.certType})"
                    if (!matchedNumbers.contains(entry)) {
                        matchedNumbers.add(entry)
                    }
                }
            }
        }

        // 매칭되는 형식승인번호는 최대 2개로 제한 (사용자 요청 사항)
        val limited = matchedNumbers.take(2)

        return if (limited.isNotEmpty()) {
            limited.joinToString(", ")
        } else {
            "형식승인 번호 매칭 데이터 없음"
        }
    }

    private fun normalize(name: String): String {
        return name.replace(Regex("[\\[\\]()주식회사]|\\(주\\)|\\(유\\)"), "").trim()
    }

    private fun extractCoreKeyword(normName: String): String {
        val tokens = normName.split(Regex("\\s+"))
        return tokens.lastOrNull() ?: normName
    }

    private fun extractModelName(formMaterial: String): String {
        val regex = Regex("모델명\\s*[:：]?\\s*([^,\\)]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(formMaterial)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        val altRegex = Regex("[A-Z0-9]{2,}-[A-Z0-9]+")
        val altMatch = altRegex.find(formMaterial)
        return altMatch?.value?.trim() ?: ""
    }
}
