package dev.rusty.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichApiTest {

    // ---- request building ---------------------------------------------------

    @Test fun bodyAlwaysHasTypeImageExifPeopleAndSize() {
        val body = JSONObject(ImmichApi.randomSearchBody(ImmichFilters(emptyList(), emptyList(), emptyList()), 30))
        assertEquals("IMAGE", body.getString("type"))
        assertTrue(body.getBoolean("withExif"))
        assertTrue(body.getBoolean("withPeople"))
        assertEquals(30, body.getInt("size"))
        // Empty categories are OMITTED entirely, never sent as [].
        assertFalse(body.has("albumIds"))
        assertFalse(body.has("personIds"))
        assertFalse(body.has("tagIds"))
    }

    @Test fun bodyIncludesSelectedIdsPerCategory() {
        val body = JSONObject(ImmichApi.randomSearchBody(
            ImmichFilters(listOf("a1", "a2"), listOf("p1"), emptyList()), 10))
        assertEquals(2, body.getJSONArray("albumIds").length())
        assertEquals("a1", body.getJSONArray("albumIds").getString(0))
        assertEquals("p1", body.getJSONArray("personIds").getString(0))
        assertFalse(body.has("tagIds"))
    }

    // ---- asset parsing ------------------------------------------------------

    private fun assetJson(
        id: String = "id1", w: Int? = 4000, h: Int? = 3000, orientation: String? = "1",
        date: String? = "2023-08-12T10:00:00.000Z", city: String? = "Turin", country: String? = "Italy",
        people: String = """[{"name":"Ana"},{"name":""}]""",
    ): String {
        val exif = buildString {
            append("{")
            append(""""exifImageWidth":${w ?: "null"},"exifImageHeight":${h ?: "null"},""")
            append(""""orientation":${orientation?.let { "\"$it\"" } ?: "null"},""")
            append(""""dateTimeOriginal":${date?.let { "\"$it\"" } ?: "null"},""")
            append(""""city":${city?.let { "\"$it\"" } ?: "null"},"country":${country?.let { "\"$it\"" } ?: "null"}""")
            append("}")
        }
        return """{"id":"$id","type":"IMAGE","exifInfo":$exif,"people":$people}"""
    }

    @Test fun parsesAssetWithMetadata() {
        val assets = ImmichApi.parseAssets("[${assetJson()}]")
        assertEquals(1, assets.size)
        val a = assets[0]
        assertEquals("id1", a.id)
        assertFalse(a.isPortrait)          // 4000x3000 landscape, orientation 1
        assertEquals("2023-08-12T10:00:00.000Z", a.takenAt)
        assertEquals("Turin, Italy", a.place)
        assertEquals(listOf("Ana"), a.people)  // blank-named people dropped
    }

    @Test fun portraitUsesPostOrientationDimensions() {
        // 4000x3000 stored landscape but orientation 6 (90° rotation) -> displayed portrait.
        assertTrue(ImmichApi.parseAssets("[${assetJson(orientation = "6")}]")[0].isPortrait)
        // Plain portrait dims.
        assertTrue(ImmichApi.parseAssets("[${assetJson(w = 3000, h = 4000)}]")[0].isPortrait)
        // Missing dims -> treated landscape (render solo).
        assertFalse(ImmichApi.parseAssets("[${assetJson(w = null, h = null)}]")[0].isPortrait)
    }

    @Test fun jsonNullsAndMissingFieldsAreNullNotTheStringNull() {
        val a = ImmichApi.parseAssets("[${assetJson(date = null, city = null, country = null)}]")[0]
        assertNull(a.takenAt)
        assertNull(a.place)
        val noExif = ImmichApi.parseAssets("""[{"id":"x","type":"IMAGE"}]""")[0]
        assertNull(noExif.takenAt)
        assertFalse(noExif.isPortrait)
    }

    @Test fun cityOnlyAndCountryOnlyPlaces() {
        assertEquals("Turin", ImmichApi.parseAssets("[${assetJson(country = null)}]")[0].place)
        assertEquals("Italy", ImmichApi.parseAssets("[${assetJson(city = null)}]")[0].place)
    }

    @Test fun malformedAssetsJsonYieldsEmptyList() {
        assertEquals(emptyList<ImmichAsset>(), ImmichApi.parseAssets("not json"))
        assertEquals(emptyList<ImmichAsset>(), ImmichApi.parseAssets("""{"error":"x"}"""))
    }

    // ---- picker parsing -----------------------------------------------------

    @Test fun parsesAlbums() {
        val json = """[{"id":"al1","albumName":"Family"},{"id":"al2","albumName":"Trips"}]"""
        assertEquals(listOf(ImmichPickerItem("al1", "Family"), ImmichPickerItem("al2", "Trips")),
            ImmichApi.parseAlbums(json))
    }

    @Test fun parseAlbumsReadsThumbAndCountWhenPresent() {
        val json = """[
          {"id":"al1","albumName":"Summer","albumThumbnailAssetId":"as9","assetCount":418},
          {"id":"al2","albumName":"Beach"}
        ]"""
        val items = ImmichApi.parseAlbums(json)
        assertEquals(2, items.size)
        assertEquals("as9", items[0].thumbAssetId)
        assertEquals(418, items[0].count)
        assertNull(items[1].thumbAssetId)
        assertNull(items[1].count)
    }

    @Test fun parseAlbumsToleratesNullThumbAndCount() {
        val json = """[{"id":"al1","albumName":"Summer","albumThumbnailAssetId":null,"assetCount":null}]"""
        val item = ImmichApi.parseAlbums(json).single()
        assertNull(item.thumbAssetId)
        assertNull(item.count)
    }

    @Test fun parsesPeoplePageDroppingHiddenAndUnnamed() {
        val json = """{"hasNextPage":true,"people":[
            {"id":"p1","name":"Ana","isHidden":false},
            {"id":"p2","name":"","isHidden":false},
            {"id":"p3","name":"Bo","isHidden":true}]}"""
        val page = ImmichApi.parsePeoplePage(json)
        assertEquals(listOf(ImmichPickerItem("p1", "Ana")), page.items)
        assertTrue(page.hasNextPage)
    }

    @Test fun parsesTagsUsingFullPathValue() {
        val json = """[{"id":"t1","name":"beach","value":"holiday/beach"}]"""
        assertEquals(listOf(ImmichPickerItem("t1", "holiday/beach")), ImmichApi.parseTags(json))
    }

    // ---- caption ------------------------------------------------------------

    @Test fun captionJoinsDatePlacePeople() {
        val a = ImmichAsset("x", false, "2023-08-12T10:00:00.000Z", "Turin, Italy", listOf("Ana", "Bo"))
        assertEquals("12 Aug 2023 · Turin, Italy · Ana, Bo", ImmichApi.captionFor(a, includePeople = true))
        assertEquals("12 Aug 2023 · Turin, Italy", ImmichApi.captionFor(a, includePeople = false))
        assertNull(ImmichApi.captionFor(ImmichAsset("x", false, null, null, emptyList()), true))
    }

    // ---- timeline (shared-album path) ---------------------------------------

    /** GET /api/timeline/bucket answers COLUMNAR: parallel arrays, not an array of objects. */
    private val bucketJson = """
        {"id":["a1","a2"],
         "ratio":[0.75,1.5],
         "isImage":[true,true],
         "isTrashed":[false,false],
         "city":["Turin",null],
         "country":["Italy",null],
         "fileCreatedAt":["2023-08-12T10:00:00.000Z","2024-01-02T09:00:00.000Z"]}
    """.trimIndent()

    @Test fun parseTimelineAssetsReadsColumnarArraysIntoAssets() {
        val assets = ImmichApi.parseTimelineAssets(bucketJson)
        assertEquals(listOf("a1", "a2"), assets.map { it.id })
        // ratio is width/height, so < 1 is portrait -- the split-view pairing depends on this.
        assertTrue(assets[0].isPortrait)
        assertFalse(assets[1].isPortrait)
        assertEquals("Turin, Italy", assets[0].place)
        assertNull(assets[1].place)
        assertEquals("2023-08-12T10:00:00.000Z", assets[0].takenAt)
        // The columnar payload carries no people at all -- captions degrade, never crash.
        assertEquals(emptyList<String>(), assets[0].people)
    }

    @Test fun parseTimelineAssetsSkipsVideosAndTrashed() {
        val json = """
            {"id":["img","vid","trash"],
             "ratio":[1.5,1.5,1.5],
             "isImage":[true,false,true],
             "isTrashed":[false,false,true]}
        """.trimIndent()
        assertEquals(listOf("img"), ImmichApi.parseTimelineAssets(json).map { it.id })
    }

    /** Short companion arrays must not throw: the id array alone drives the row count. */
    @Test fun parseTimelineAssetsToleratesRaggedAndMalformedPayloads() {
        val ragged = """{"id":["a1","a2"],"ratio":[0.5],"city":[]}"""
        assertEquals(listOf("a1", "a2"), ImmichApi.parseTimelineAssets(ragged).map { it.id })
        assertEquals(emptyList<ImmichAsset>(), ImmichApi.parseTimelineAssets("not json"))
        assertEquals(emptyList<ImmichAsset>(), ImmichApi.parseTimelineAssets("[]"))
    }

    @Test fun parseTimelineBucketsReadsTimeBucketAndCount() {
        val json = """[{"timeBucket":"2024-01-01","count":42},{"timeBucket":"2023-12-01","count":7}]"""
        val buckets = ImmichApi.parseTimelineBuckets(json)
        assertEquals(listOf("2024-01-01", "2023-12-01"), buckets.map { it.timeBucket })
        assertEquals(listOf(42, 7), buckets.map { it.count })
        assertEquals(emptyList<ImmichBucket>(), ImmichApi.parseTimelineBuckets("nope"))
    }

    // ---- user parsing -------------------------------------------------------

    @Test fun parseUserReadsNameAndEmail() {
        val u = ImmichApi.parseUser("""{"id":"u1","email":"jose@mail.com","name":"Jose"}""")
        assertEquals(ImmichUser("u1", "Jose", "jose@mail.com"), u)
    }

    @Test fun parseUserBlankNameLeavesEmailForCallerFallback() {
        // name present-but-empty stays empty; the display-name fallback lives in the caller, not here.
        val u = ImmichApi.parseUser("""{"id":"u1","email":"jose@mail.com","name":""}""")
        assertEquals("", u?.name)
        assertEquals("jose@mail.com", u?.email)
    }

    @Test fun parseUserNullNameBecomesEmptyString() {
        val u = ImmichApi.parseUser("""{"id":"u1","email":"jose@mail.com","name":null}""")
        assertEquals("", u?.name)
    }

    @Test fun parseUserMissingIdIsNull() {
        assertNull(ImmichApi.parseUser("""{"email":"jose@mail.com","name":"Jose"}"""))
    }

    @Test fun parseUserMalformedIsNull() {
        assertNull(ImmichApi.parseUser("not json"))
    }
}
