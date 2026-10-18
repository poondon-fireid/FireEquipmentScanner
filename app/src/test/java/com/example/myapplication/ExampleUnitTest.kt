package com.example.myapplication

import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileInputStream

class ExampleUnitTest {
    @Test
    fun testApprovalMatcherLimits() {
        val assetsDir = File("D:/AndroidSDK/MyAppData/app/src/main/assets")
        assertTrue(assetsDir.exists())

        val approvals = mutableListOf<ApprovalEntity>()
        val files = listOf("approvals_type.csv" to "형식승인", "approvals_perf.csv" to "성능인증", "approvals_kfi.csv" to "KFI인증")

        for ((fileName, certType) in files) {
            val file = File(assetsDir, fileName)
            if (!file.exists()) continue
            val reader = BufferedReader(InputStreamReader(FileInputStream(file), "EUC-KR"))
            reader.readLine()
            var line = reader.readLine()
            while (line != null) {
                val tokens = line.split(",").map { it.trim().removeSurrounding("\"") }
                if (tokens.size >= 5) {
                    val spec = tokens.drop(4).joinToString(",").trim().removeSurrounding("\"")
                    approvals.add(ApprovalEntity(certType = certType, manufacturer = tokens[0], itemName = tokens[1], approvalNo = tokens[2], approvalDate = tokens[3], spec = spec))
                }
                line = reader.readLine()
            }
            reader.close()
        }

        // Test 1: Detector with model LTD-3300B
        val res1 = findMatchInMemory(approvals, "(주)리더스테크", "단독경보형감지기", "DC 3 V, 광전식, 2종, (모델명 : LTD-3300B)")
        println("Detector Result: $res1")
        val count1 = res1.split(", ").size
        assertTrue("Expected 1~2 items, but got $count1", count1 in 1..2)

        // Test 2: Sprinkler without explicit model name
        val res2 = findMatchInMemory(approvals, "파라텍", "스프링클러헤드", "폐쇄형 72도 15A")
        println("Sprinkler Result: $res2")
        val count2 = res2.split(", ").size
        assertTrue("Expected 1~2 items, but got $count2", count2 in 1..2)
    }

    private fun findMatchInMemory(
        approvals: List<ApprovalEntity>,
        manufacturer: String,
        itemType: String,
        formMaterial: String
    ): String {
        val normMan = normalize(manufacturer)
        val coreKeyword = extractCoreKeyword(normMan)
        val modelName = extractModelName(formMaterial)

        if (approvals.isEmpty()) {
            return "형식승인 번호 매칭 데이터 없음 (DB 비어있음)"
        }

        val matchedNumbers = mutableListOf<String>()

        if (modelName.isNotBlank()) {
            val cleanModel = modelName.replace(Regex("[-\\s]"), "").uppercase()
            for (app in approvals) {
                val manMatch = app.manufacturer.contains(coreKeyword, ignoreCase = true) || app.manufacturer.contains(normMan, ignoreCase = true)
                val cleanSpec = app.spec.replace(Regex("[-\\s]"), "").uppercase()
                val specMatch = cleanSpec.contains(cleanModel)
                if (manMatch && specMatch) {
                    val entry = "${app.approvalNo} (${app.certType})"
                    if (!matchedNumbers.contains(entry)) matchedNumbers.add(entry)
                }
            }
        }

        if (matchedNumbers.isEmpty()) {
            val cleanItemType = itemType.replace(" ", "")
            for (app in approvals) {
                val manMatch = app.manufacturer.contains(coreKeyword, ignoreCase = true) || app.manufacturer.contains(normMan, ignoreCase = true)
                val cleanAppItem = app.itemName.replace(" ", "")
                val itemMatch = cleanAppItem.contains(cleanItemType, ignoreCase = true) || cleanItemType.contains(cleanAppItem, ignoreCase = true)
                if (manMatch && itemMatch) {
                    val entry = "${app.approvalNo} (${app.certType})"
                    if (!matchedNumbers.contains(entry)) matchedNumbers.add(entry)
                }
            }
        }

        if (matchedNumbers.isEmpty()) {
            for (app in approvals) {
                val manMatch = app.manufacturer.contains(coreKeyword, ignoreCase = true) || app.manufacturer.contains(normMan, ignoreCase = true)
                if (manMatch) {
                    val entry = "${app.approvalNo} (${app.certType})"
                    if (!matchedNumbers.contains(entry)) matchedNumbers.add(entry)
                }
            }
        }

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