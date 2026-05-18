package org.skepsun.kototoro.parsers.site.all

import androidx.collection.ArraySet
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

@ContentSourceParser("Danbooru", "danbooru.donmai.us")
internal class DanbooruParser(context: ContentLoaderContext) : PagedContentParser(
	context = context,
	source = ContentParserSource.DANBOORU,
	pageSize = 60,
	searchPageSize = 20,
), ContentParserAuthProvider {


	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
            isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = ContentListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		val selectedTags = mutableListOf<String>()

		if (!filter.query.isNullOrEmpty()) {
			selectedTags.addAll(filter.query.split(" ").filter { it.isNotBlank() })
		}

		selectedTags.addAll(filter.tags.map { it.key })
		selectedTags.addAll(filter.tagsExclude.map { "-" + it.key })

		val finalTags = mutableListOf<String>()

		// Max 2 tags for free users.
		if (selectedTags.isNotEmpty()) {
			val tagsJson = JSONArray(webClient.httpGet(
				"https://$domain/tags.json?search[name_comma]=${selectedTags.joinToString(",")}"
			).body())

			val tagsCountMap = mutableMapOf<String, Int>()
			tagsJson.mapToList { obj ->
				tagsCountMap[obj.getString("name")] = obj.getInt("post_count")
			}

			val sortedTags = selectedTags.sortedBy { tag ->
				val cleanTag = tag.removePrefix("-")
				tagsCountMap[cleanTag] ?: Int.MAX_VALUE
			}

			val maxTags = if (order != SortOrder.NEWEST) 1 else 2
			finalTags.addAll(sortedTags.take(maxTags))
		}

		when (order) {
			SortOrder.UPDATED -> finalTags.add("order:change")
			SortOrder.POPULARITY -> finalTags.add("order:score")
			SortOrder.NEWEST -> { /* nothing */ }
			else -> { /* nothing */ }
		}

		if (filter.contentRating.isNotEmpty()) {
			filter.contentRating.oneOrThrowIfMany()?.let {
				when (it) {
					ContentRating.SAFE -> finalTags.add("rating:g")
					ContentRating.SUGGESTIVE -> finalTags.add("rating:s,q") // search for both sensitive and questionable
					ContentRating.ADULT -> finalTags.add("rating:e") // explicit maps to adult
					else -> { /* nothing */ }
				}
			}
		}

		val tagsParam = finalTags.joinToString(" ")
		val tagsEncoded = tagsParam.urlEncoded()
		val url = "https://$domain/posts.json?tags=$tagsEncoded&page=$page"

		return parseList(url, page)
	}

	override suspend fun getDetails(manga: Content): Content {
		val chapter = ContentChapter(
			id = generateUid("${manga.url}|image"),
			url = manga.url,
			title = "Image",
			number = 1f,
			uploadDate = 0L,
			volume = 0,
			branch = null,
			scanlator = null,
			source = source,
		)

		return manga.copy(
			title = manga.title,
			contentRating = manga.contentRating,
			largeCoverUrl = manga.largeCoverUrl,
			description = null,
			tags = manga.tags,
			state = ContentState.FINISHED,
			authors = manga.authors,
			chapters = listOf(chapter)
		)
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		val postId = chapter.url.substringAfterLast("/")
		val apiUrl = "https://$domain/posts/$postId.json"
		
		val json = webClient.httpGet(apiUrl).parseJson() as JSONObject
		
		// Get image URL - try the different image file URL fields
		val imageUrl = json.optString("file_url", "")
			.takeIf { it.isNotEmpty() }
			?: json.optString("large_file_url", "")
			.takeIf { it.isNotEmpty() }
			?: json.optString("preview_file_url", "")
			.takeIf { it.isNotEmpty() }
			?: throw ParseException("Cannot find image URL", apiUrl)
		
		val absoluteImageUrl = imageUrl.toAbsoluteUrlOrNull(domain) ?: imageUrl
		
		return listOf(
			ContentPage(
				id = generateUid(absoluteImageUrl),
				url = absoluteImageUrl,
				preview = null,
				source = source,
			)
		)
	}

	private suspend fun fetchAvailableTags(name: String? = null): Set<ContentTag> {
		val nameParam = name ?: ""
		val tagsJson = JSONArray(webClient.httpGet(
			"https://$domain/tags.json?search[name_or_alias_matches]=${nameParam}*&search[order]=count",
		).body())
		val result = mutableSetOf<ContentTag>()
		tagsJson.mapToList { obj ->
			result.add(ContentTag(
				title = obj.getString("name").toTitleCase(Locale.ENGLISH),
				key = obj.getString("name"),
				source = source
			))
		}
		return result
	}

	private suspend fun parseList(url: String, page: Int): List<Content> {
		val jsonBody = webClient.httpGet(url).body()
		if (jsonBody.isBlank()) return emptyList()

		val json = JSONArray(jsonBody)
		if (json == null) {
			return emptyList()
		}

		return json.mapToList { obj ->
            val ratingStr = obj.optString("rating", "")
			val contentRating = when (ratingStr) {
                "g" -> ContentRating.SAFE
                "s", "q" -> ContentRating.SUGGESTIVE
                "e" -> ContentRating.ADULT
				else -> null
            }

			val tagsArray = obj.optString("tag_string", "").split(" ").filter { it.isNotBlank() }
			val tags = tagsArray.map {
				ContentTag(it.toTitleCase(Locale.ENGLISH), it, source)
			}.toSet()

			val authorsArray = obj.optString("tag_string_artist", "").split(" ").filter { it.isNotBlank() }
			val authors = authorsArray.map { it.toTitleCase(Locale.ENGLISH) }.toSet()

			Content(
				id = obj.getLong("id"),
				title = obj.optString("title", "").ifEmpty { "Post ${obj.getLong("id")}" },
				altTitles = emptySet(),
				url = domain + "/post/" + obj.getLong("id"),
				publicUrl = domain + "/post/" + obj.getLong("id"),
				rating = RATING_UNKNOWN,
				contentRating = contentRating,
				coverUrl = obj.optString("preview_file_url", "").ifEmpty { obj.optString("file_url", "") },
				largeCoverUrl = obj.optString("large_file_url", "").ifEmpty { obj.optString("file_url", "") },
				description = null,
				tags = tags,
				state = null,
				authors = authors,
				source = source
			)
		}
	}
}
