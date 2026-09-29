package us.wangxy.voicebook.server.bff

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import us.wangxy.voicebook.server.bff.config.CalibreConfig
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

/** Minimal calibre library + CWA app.db with the columns the BFF touches. */
fun createCalibreFixture(): CalibreConfig {
    val root = Files.createTempDirectory("bff-calibre").toFile()
    val library = File(root, "library").apply { mkdirs() }
    val appDb = File(root, "app.db")

    DriverManager.getConnection("jdbc:sqlite:${File(library, "metadata.db").absolutePath}").use { c ->
        c.createStatement().use { st ->
            listOf(
                "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, sort TEXT, timestamp TEXT, pubdate TEXT, series_index REAL, author_sort TEXT, path TEXT, has_cover INTEGER)",
                "CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE books_authors_link (id INTEGER PRIMARY KEY, book INTEGER, author INTEGER)",
                "CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE books_tags_link (id INTEGER PRIMARY KEY, book INTEGER, tag INTEGER)",
                "CREATE TABLE series (id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE books_series_link (id INTEGER PRIMARY KEY, book INTEGER, series INTEGER)",
                "CREATE TABLE comments (id INTEGER PRIMARY KEY, book INTEGER, text TEXT)",
                "CREATE TABLE data (id INTEGER PRIMARY KEY, book INTEGER, format TEXT, name TEXT)",
                "INSERT INTO books VALUES (1, '三体', '三体', '2026-01-01 00:00:00', '2008-01-01', 1.0, '刘, 慈欣', '刘慈欣/三体 (1)', 1)",
                "INSERT INTO books VALUES (2, 'Dune', 'Dune', '2026-02-01 00:00:00', '1965-01-01', 1.0, 'Herbert, Frank', 'Frank Herbert/Dune (2)', 0)",
                "INSERT INTO authors VALUES (1, '刘慈欣'), (2, 'Frank Herbert')",
                "INSERT INTO books_authors_link VALUES (1, 1, 1), (2, 2, 2)",
                "INSERT INTO tags VALUES (1, '科幻')",
                "INSERT INTO books_tags_link VALUES (1, 1, 1), (2, 2, 1)",
                "INSERT INTO series VALUES (1, '地球往事')",
                "INSERT INTO books_series_link VALUES (1, 1, 1)",
                "INSERT INTO comments VALUES (1, 1, '<p>简介</p>')",
                "INSERT INTO data VALUES (1, 1, 'EPUB', '三体 - 刘慈欣')",
            ).forEach(st::execute)
        }
    }
    File(library, "刘慈欣/三体 (1)").apply {
        mkdirs()
        File(this, "cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(this, "三体 - 刘慈欣.epub").writeText("epub")
    }

    DriverManager.getConnection("jdbc:sqlite:${appDb.absolutePath}").use { c ->
        c.createStatement().use { st ->
            listOf(
                "CREATE TABLE user (id INTEGER PRIMARY KEY, name TEXT, email TEXT)",
                "CREATE TABLE bookmark (id INTEGER PRIMARY KEY, user_id INTEGER, book_id INTEGER, format TEXT, bookmark_key TEXT)",
                "CREATE TABLE kobo_reading_state (id INTEGER PRIMARY KEY, user_id INTEGER, book_id INTEGER, last_modified TEXT, priority_timestamp TEXT)",
                "CREATE TABLE kobo_bookmark (id INTEGER PRIMARY KEY, kobo_reading_state_id INTEGER, last_modified TEXT, location_source TEXT, location_type TEXT, location_value TEXT, progress_percent REAL, content_source_progress_percent REAL)",
                "INSERT INTO user VALUES (1, 'admin', 'a@x'), (2, 'alice', 'alice@x')",
            ).forEach(st::execute)
        }
    }
    return CalibreConfig(libraryDir = library, appDb = appDb, webUrl = "http://cwa.invalid")
}
