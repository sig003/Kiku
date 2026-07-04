package com.bradlab.kiku

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * 클립 데이터 공급원 (DESIGN.md §3, §10.1).
 *
 * interface로 둬서 MVP는 [AssetClipRepository](번들 JSON), 추후 원격(RemoteClipRepository)으로
 * 구현체만 교체 — 호출부는 안 바뀐다.
 */
interface ClipRepository {
    /** 모든 클립을 id 순으로. */
    suspend fun clips(): List<Clip>
    /** id로 한 클립. 없으면 null. */
    suspend fun clip(id: Int): Clip?
}

/**
 * assets/clips/ 아래 *.json 을 읽어 파싱하는 MVP 구현.
 * 각 파일 = Clip 하나(JSON 객체). 파싱 실패한 파일은 건너뛰고 로그만 남긴다.
 */
class AssetClipRepository(
    private val context: Context,
    private val dir: String = "clips",
) : ClipRepository {

    private val json = Json { ignoreUnknownKeys = true }

    // 앱 실행 중 assets는 안 바뀌므로 1회 로드 후 캐시.
    private var cache: List<Clip>? = null

    companion object {
        /** 실제 클립이 아닌 "전체 랜덤" 합성 클립의 id. */
        const val RANDOM_CLIP_ID = 0
    }

    override suspend fun clips(): List<Clip> {
        cache?.let { return it }
        val names = runCatching { context.assets.list(dir)?.toList() }.getOrNull().orEmpty()
        val loaded = names
            .filter { it.endsWith(".json") }
            .mapNotNull { name -> parse(name) }
            .sortedBy { it.id }
        cache = loaded
        return loaded
    }

    override suspend fun clip(id: Int): Clip? = clips().firstOrNull { it.id == id }

    /**
     * 문장을 섞어 [count]개만 뽑은 "랜덤" 클립. 열 때마다 새로 섞인다.
     * [level]이 "N4"/"N3"이면 그 레벨 클립에서만, null/"전체"면 전 레벨에서 뽑는다.
     */
    suspend fun randomClip(count: Int = 100, level: String? = null): Clip {
        val all = if (level == null || level == "전체") "전체" else level
        val pool = clips().filter { all == "전체" || it.level == all }
        val picked = pool.flatMap { it.sentences }
            .shuffled()
            .take(count)
            .mapIndexed { i, s -> s.copy(id = i + 1) }
        return Clip(
            id = RANDOM_CLIP_ID,
            category = "랜덤",
            title = "${all} 랜덤 ${picked.size}문장",
            mode = ClipMode.DRILL,
            sentences = picked,
        )
    }

    private fun parse(fileName: String): Clip? = try {
        val text = context.assets.open("$dir/$fileName").bufferedReader().use { it.readText() }
        json.decodeFromString<Clip>(text)
    } catch (e: Exception) {
        Log.w("KikuClips", "클립 파싱 실패: $dir/$fileName", e)
        null
    }
}
