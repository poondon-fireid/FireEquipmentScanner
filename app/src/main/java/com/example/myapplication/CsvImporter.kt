package com.example.myapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvImporter {
    suspend fun importCsvDataIfEmpty(context: Context) {
        val db = AppDatabase.getDatabase(context)

        withContext(Dispatchers.IO) {
            try {
                // 1. 형식승인/성능인증/KFI인증 통합 임포트 (다양한 파일명 대응)
                if (db.approvalDao().getCount() == 0) {
                    importApprovals(
                        context, db,
                        listOf("approvals_type.csv", "소방용품+형식승인+정보_20261013.csv", "소방용품_형식승인_정보.csv"),
                        "형식승인"
                    )
                    importApprovals(
                        context, db,
                        listOf("approvals_perf.csv", "소방용품+성능인증+정보_20261013.csv", "소방용품_성능인증_정보.csv"),
                        "성능인증"
                    )
                    importApprovals(
                        context, db,
                        listOf("approvals_kfi.csv", "소방용품+KFI인증+승인정보_20261013.csv", "소방장비+인증정보_20261013.csv", "소방장비_인증정보.csv"),
                        "KFI인증"
                    )
                }

                // 2. 제조업체 정보 임포트
                if (db.manufacturerDao().getCount() == 0) {
                    val manFiles = listOf("manufacturers.csv", "형식승인제조업체정보_20261013.csv", "형식승인제조업체정보.csv")
                    for (fileName in manFiles) {
                        try {
                            val inputStream = context.assets.open(fileName)
                            val reader = BufferedReader(InputStreamReader(inputStream, "EUC-KR"))
                            val list = mutableListOf<ManufacturerEntity>()
                            reader.readLine() // Header skip
                            var line = reader.readLine()
                            while (line != null) {
                                val tokens = line.split(",").map { it.trim().removeSurrounding("\"") }
                                if (tokens.size >= 6) {
                                    list.add(
                                        ManufacturerEntity(
                                            name = tokens[0],
                                            representative = tokens[1],
                                            website = tokens[2],
                                            address = tokens[3],
                                            phone = tokens[4],
                                            items = tokens.drop(5).joinToString(",")
                                        )
                                    )
                                }
                                line = reader.readLine()
                            }
                            reader.close()
                            db.manufacturerDao().insertAll(list)
                            Log.d("CsvImporter", "Loaded ${list.size} manufacturers from $fileName.")
                            break
                        } catch (e: Exception) {
                            Log.d("CsvImporter", "Could not open $fileName, trying next...")
                        }
                    }
                }

                // 3. 취소 정보 통합 임포트
                if (db.cancellationDao().getCount() == 0) {
                    val cancelFiles = listOf(
                        "cancellations_type.csv", "소방용품형식승인취소정보_20261013.csv", "소방용품형식승인취소정보 (1).csv",
                        "cancellations_perf.csv", "소방용품성능인증취소정보_20261013.csv",
                        "cancellations_kfi.csv", "소방용품KFI인증취소현황_20261013.csv",
                        "cancellations.csv"
                    )
                    for (fileName in cancelFiles) {
                        importCancellations(context, db, fileName)
                    }
                }
            } catch (e: Exception) {
                Log.e("CsvImporter", "Import failed", e)
            }
            Unit
        }
    }

    private suspend fun importApprovals(context: Context, db: AppDatabase, possibleFileNames: List<String>, certType: String) {
        for (fileName in possibleFileNames) {
            try {
                val inputStream = context.assets.open(fileName)
                val reader = BufferedReader(InputStreamReader(inputStream, "EUC-KR"))
                val list = mutableListOf<ApprovalEntity>()
                reader.readLine() // Header skip
                var line = reader.readLine()
                while (line != null) {
                    val tokens = line.split(",").map { it.trim().removeSurrounding("\"") }
                    if (tokens.size >= 5) {
                        val spec = tokens.drop(4).joinToString(",").trim().removeSurrounding("\"")
                        list.add(
                            ApprovalEntity(
                                certType = certType,
                                manufacturer = tokens[0],
                                itemName = tokens[1],
                                approvalNo = tokens[2],
                                approvalDate = tokens[3],
                                spec = spec
                            )
                        )
                    }
                    line = reader.readLine()
                }
                reader.close()
                db.approvalDao().insertAll(list)
                Log.d("CsvImporter", "Loaded ${list.size} $certType approvals from $fileName.")
                return
            } catch (e: Exception) {
                Log.d("CsvImporter", "Could not open $fileName, trying next...")
            }
        }
    }

    private suspend fun importCancellations(context: Context, db: AppDatabase, fileName: String) {
        try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream, "EUC-KR"))
            val list = mutableListOf<CancellationEntity>()
            reader.readLine() // Header skip
            var line = reader.readLine()
            while (line != null) {
                val tokens = line.split(",").map { it.trim().removeSurrounding("\"") }
                if (tokens.size >= 4) {
                    val reason = if (tokens.size >= 6) tokens.drop(5).joinToString(",") else tokens.getOrNull(5) ?: ""
                    list.add(
                        CancellationEntity(
                            no = tokens[0],
                            manufacturer = tokens.getOrNull(1) ?: "",
                            itemName = tokens.getOrNull(2) ?: "",
                            approvalNo = tokens.getOrNull(3) ?: "",
                            cancelDate = tokens.getOrNull(4) ?: "",
                            reason = reason
                        )
                    )
                }
                line = reader.readLine()
            }
            reader.close()
            db.cancellationDao().insertAll(list)
            Log.d("CsvImporter", "Loaded ${list.size} cancellations from $fileName.")
        } catch (e: Exception) {
            Log.d("CsvImporter", "$fileName load failed (might not exist)", e)
        }
    }
}
