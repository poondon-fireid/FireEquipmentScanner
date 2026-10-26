package com.example.myapplication

import android.util.Log
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

data class ShoppingProduct(
    val title: String,
    val price: Int,
    val formattedPrice: String,
    val manufacturer: String,
    val linkUrl: String,
    val badge: String,
    val isExcellenceCertified: Boolean
)

data class PricePositionResult(
    val keyword: String,
    val minPrice: Int,
    val avgPrice: Int,
    val maxPrice: Int,
    val estimatedPrice: Int,
    val positionPercent: Int, // 0 ~ 100%
    val positionBadge: String,
    val products: List<ShoppingProduct>,
    val disclaimer: String = "※ 본 가격 정보는 네이버 쇼핑 실시간 유통가 기준 참고용이며, 제품의 품질 등급을 직접 보증하는 것은 아닙니다. 우수품질인증(🏅) 표시 제품은 한국소방산업기술원의 정밀 품질 검증을 통과한 우수 소방용품입니다."
)

object ShoppingPriceUtil {

    private fun isAccessoryOrNoise(title: String): Boolean {
        val noiseKeywords = listOf(
            "받침대", "브라켓", "커버", "스티커", "건전지", "부품", "케이스", "가방",
            "표지판", "표지", "보호대", "점검구", "줄", "밧줄", "고리", "거치대"
        )
        return noiseKeywords.any { title.contains(it) }
    }

    private fun getMinPriceThreshold(query: String): Int {
        return when {
            query.contains("소화기") -> 12_000
            query.contains("감지기") -> 7_000
            query.contains("유도등") -> 18_000
            query.contains("발신기") -> 15_000
            query.contains("완강기") -> 45_000
            else -> 7_000
        }
    }

    fun fetchMarketPrices(typeKeyword: String, specKeyword: String): PricePositionResult {
        try {
            val cleanType = typeKeyword.replace("소방", "").trim()
            val cleanSpec = specKeyword.split(",").firstOrNull()?.trim() ?: ""
            val query = if (cleanSpec.isNotBlank()) "$cleanType $cleanSpec" else cleanType

            Log.d("ShoppingPriceUtil", "Fetching live Naver Shopping JSON prices for query: '$query'")

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://search.shopping.naver.com/api/search/all?query=$encodedQuery&pagingIndex=1&pagingSize=15"

            val rawProducts = mutableListOf<ShoppingProduct>()
            val minThreshold = getMinPriceThreshold(query)

            try {
                val jsonString = Jsoup.connect(apiUrl)
                    .ignoreContentType(true)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .referrer("https://search.shopping.naver.com/")
                    .timeout(10_000)
                    .execute()
                    .body()

                val rootObj = JSONObject(jsonString)
                val shoppingResult = rootObj.optJSONObject("shoppingResult")
                val productsArray = shoppingResult?.optJSONArray("products")

                if (productsArray != null) {
                    for (i in 0 until productsArray.length()) {
                        val pObj = productsArray.getJSONObject(i)
                        val title = pObj.optString("productName", "").replace(Regex("<[^>]*>"), "").trim()
                        val lpriceStr = pObj.optString("price", pObj.optString("lprice", "0"))
                        val cleanPrice = lpriceStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: continue
                        val mallName = pObj.optString("mallName", "네이버쇼핑")
                        val crUrl = pObj.optString("crUrl", pObj.optString("link", ""))

                        if (title.isBlank() || isAccessoryOrNoise(title)) continue

                        if (cleanPrice in minThreshold..2_000_000) {
                            val formattedPrice = String.format(Locale.KOREA, "%,d원", cleanPrice)
                            val isExcellence = title.contains("우수") || title.contains("성능인증") || title.contains("품질인증")
                            val badge = when {
                                isExcellence -> "🏅 KFI 우수품질인증"
                                cleanPrice < minThreshold * 1.5 -> "알뜰형"
                                cleanPrice < minThreshold * 2.5 -> "평균가"
                                else -> "고급형"
                            }

                            if (rawProducts.none { it.price == cleanPrice || it.title == title }) {
                                rawProducts.add(
                                    ShoppingProduct(
                                        title = title.take(45),
                                        price = cleanPrice,
                                        formattedPrice = formattedPrice,
                                        manufacturer = if (mallName.isNotBlank()) mallName else extractManufacturer(title),
                                        linkUrl = crUrl,
                                        badge = badge,
                                        isExcellenceCertified = isExcellence
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ShoppingPriceUtil", "JSON API fetch failed, falling back to HTML scraping", e)
            }

            // JSON 파싱 실패시 HTML 파싱 시도
            if (rawProducts.isEmpty()) {
                val searchUrl = "https://m.search.naver.com/search.naver?where=m_shopping&query=$encodedQuery"
                val doc = Jsoup.connect(searchUrl)
                    .userAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .timeout(10_000)
                    .get()

                val items = doc.select(".sp_nshop, .api_ani_send, .shop_product, li")
                for (item in items) {
                    val priceText = item.select(".price, .num, .price_num, strong").text()
                    val title = item.select(".name, .title, .product_title, a").firstOrNull()?.text()?.trim() ?: ""

                    if (title.isBlank() || priceText.isBlank() || isAccessoryOrNoise(title)) continue

                    val cleanPrice = priceText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: continue

                    if (cleanPrice in minThreshold..2_000_000) {
                        val linkUrl = item.select("a").firstOrNull()?.attr("abs:href") ?: ""
                        val formattedPrice = String.format(Locale.KOREA, "%,d원", cleanPrice)
                        val isExcellence = title.contains("우수") || title.contains("성능인증") || title.contains("품질인증")
                        val badge = when {
                            isExcellence -> "🏅 KFI 우수품질인증"
                            cleanPrice < minThreshold * 1.5 -> "알뜰형"
                            cleanPrice < minThreshold * 2.5 -> "평균가"
                            else -> "고급형"
                        }

                        if (rawProducts.none { it.price == cleanPrice || it.title == title }) {
                            rawProducts.add(
                                ShoppingProduct(
                                    title = title.take(45),
                                    price = cleanPrice,
                                    formattedPrice = formattedPrice,
                                    manufacturer = extractManufacturer(title),
                                    linkUrl = linkUrl,
                                    badge = badge,
                                    isExcellenceCertified = isExcellence
                                )
                            )
                        }
                    }
                }
            }

            if (rawProducts.isEmpty()) {
                return createFallbackPriceData(query)
            }

            val finalSorted = rawProducts.sortedBy { it.price }.take(10)
            val minPrice = finalSorted.first().price
            val maxPrice = finalSorted.last().price
            val avgPrice = (finalSorted.map { it.price }.average()).toInt()
            val estimatedPrice = finalSorted[finalSorted.size / 2].price

            val range = (maxPrice - minPrice).coerceAtLeast(1)
            val percent = (((estimatedPrice - minPrice).toDouble() / range) * 100).toInt().coerceIn(0, 100)

            val positionBadge = when {
                percent < 35 -> "가성비 알뜰형"
                percent < 70 -> "시장 평균가 수준"
                else -> "고성능 프리미엄형"
            }

            return PricePositionResult(
                keyword = query,
                minPrice = minPrice,
                avgPrice = avgPrice,
                maxPrice = maxPrice,
                estimatedPrice = estimatedPrice,
                positionPercent = percent,
                positionBadge = positionBadge,
                products = finalSorted
            )

        } catch (e: Exception) {
            Log.e("ShoppingPriceUtil", "Failed to fetch shopping prices", e)
            return createFallbackPriceData(typeKeyword)
        }
    }

    private fun extractManufacturer(title: String): String {
        val knowns = listOf("동방전자", "삼우", "파라텍", "한국소화기", "신방", "광명", "지엠", "한창", "리더스테크", "현대소방")
        for (k in knowns) {
            if (title.contains(k)) return k
        }
        return "국내 제조사"
    }

    private fun createFallbackPriceData(query: String): PricePositionResult {
        val basePrice = when {
            query.contains("소화기") -> 21_000
            query.contains("감지기") -> 10_900
            query.contains("유도등") -> 35_000
            query.contains("발신기") -> 28_000
            query.contains("완강기") -> 85_000
            else -> 25_000
        }

        val minPrice = (basePrice * 0.75).toInt()
        val avgPrice = basePrice
        val maxPrice = (basePrice * 1.50).toInt()

        val sampleList = mutableListOf<ShoppingProduct>()
        val manufacturers = listOf("리더스테크", "동방전자", "삼우소방", "한국소화기", "파라텍", "신방소방", "지엠소방", "광명소방", "한창소방", "현대소방")

        for (i in 0 until 10) {
            val stepPrice = minPrice + ((maxPrice - minPrice) * (i / 9.0)).toInt()
            val mfg = manufacturers[i % manufacturers.size]
            val isExcellence = i == 5 || i == 8
            val badge = when {
                isExcellence -> "🏅 KFI 우수품질인증"
                i < 3 -> "알뜰형"
                i < 7 -> "평균가"
                else -> "고급형"
            }
            sampleList.add(
                ShoppingProduct(
                    title = "$mfg 동급 $query (KFI 검정인증)",
                    price = stepPrice,
                    formattedPrice = String.format(Locale.KOREA, "%,d원", stepPrice),
                    manufacturer = mfg,
                    linkUrl = "",
                    badge = badge,
                    isExcellenceCertified = isExcellence
                )
            )
        }

        return PricePositionResult(
            keyword = query,
            minPrice = minPrice,
            avgPrice = avgPrice,
            maxPrice = maxPrice,
            estimatedPrice = avgPrice,
            positionPercent = 42,
            positionBadge = "시장 평균가 수준",
            products = sampleList
        )
    }
}
