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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SENSITIVE, ContentRating.QUESTIONABLE, ContentRating.EXPLICIT),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
	
                !filter.query.isNullOrEmpty() -> {
                    // convert filter.query in tags
			    }

				val url = buildString {
					append("https://")
					append(domain)

					append("/posts.json?tags=")
					when (order) {
						SortOrder.UPDATED -> append("order:change")
						SortOrder.POPULARITY -> append("order:score")
						SortOrder.NEWEST -> append("")
						else -> append("")
					}

					

					if (filter.contentRating.isNotEmpty()) {
						filter.contentRating.oneOrThrowIfMany()?.let {
							append(
								when (it) {
									ContentRating.SAFE -> append("rating:g"),
                                    ContentRating.SENSITIVE -> append("rating:s"),
                                    ContentRating.QUESTIONABLE -> append("rating:q"),
                                    ContentRating.EXPLICIT -> append("rating:e"),
									else -> append("")
								},
							)
						}
					}

					append("&page=")
					append(page.toString())
				}

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

	private suspend fun fetchAvailableTags(name: String = null): Set<ContentTag> {
		val tags = JSONArray(webClient.httpGet(
			"https://${domain}/tags.json?search[name_or_alias_matches]=${name}*&search[order]=count",
		).body())
		val result = JSONArray()
		return json.mapToList { obj ->
			
				result.append(ContentTag(
					title = obj.getString("name").toTitleCase(Locale.ENGLISH),
					))
			
			
		}
		return result
		throw ParseException("Cannot find gernes list", scripts[0].baseUri())
	}

	
	private suspend fun parseList(url: String, page: Int): List<Content> {
		val json = JSONArray(webClient.httpGet(url).body())
		if (json == null) {
			return emptyList()
		}

		return json.mapToList { obj ->
            val rating = RATING_UNKNOWN
            when {
                obj.getString("rating") == "g" -> rating = RATING_SAFE,
                obj.getString("rating") == "s" -> rating = RATING_SENSITIVE,
                obj.getString("rating") == "q" -> rating = RATING_QUESTIONABLE,
                obj.getString("rating") == "e" -> rating = RATING_EXPLICIT
            }
			Content(
				id = obj.getString("id"),
				title = obj.optString("title", ""),
				altTitles = null,
				url = domain + "/post/" + obj.getString("id"),
				publicUrl = domain + "/post/" + obj.getString("id"),
				rating = rating,
				contentRating = null,
				coverUrl = obj.optString("preview_file_url", "file_url"),
				largeCoverUrl = obj.optString("large_file_url", "file_url"),
				description = null,
				tags = obj.optJSONArray("tag_string", JSONArray()),
				state = null,
				authors = obj.optJSONArray("tag_string_artist", JSONArray()),
				source = obj.optString("source", ""),,
			)
		}
	}

	private fun Element.parseTags() = children().mapToSet { span ->
		val text = span.ownText()
		ContentTag(
			title = text.toTitleCase(),
			key = text.lowercase(Locale.ENGLISH).replace(' ', '_'),
			source = source,
		)
	}




}
