package com.example.musicplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { PlayerApp() }
            }
        }
    }
}

fun loadSongs(context: Context): List<Song> {
    val list = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST
    )
    context.contentResolver.query(
        collection,
        projection,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        null,
        "${MediaStore.Audio.Media.TITLE} ASC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            list.add(
                Song(
                    id = id,
                    title = c.getString(titleCol) ?: "Tanpa judul",
                    artist = c.getString(artistCol) ?: "Unknown",
                    uri = ContentUris.withAppendedId(collection, id)
                )
            )
        }
    }
    return list
}

fun fmt(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PlayerApp() {
    val context = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm) }

    var songs by remember { mutableStateOf(emptyList<Song>()) }
    LaunchedEffect(granted) {
        if (granted) songs = withContext(Dispatchers.IO) { loadSongs(context) }
    }

    val mp = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { mp.release() } }

    var index by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var dur by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }

    fun play(i: Int) {
        if (songs.isEmpty()) return
        val n = (i + songs.size) % songs.size
        try {
            mp.reset()
            mp.setDataSource(context, songs[n].uri)
            mp.setOnPreparedListener {
                it.start()
                dur = it.duration
                pos = 0
                playing = true
            }
            mp.setOnCompletionListener { play(n + 1) }
            mp.prepareAsync()
            index = n
        } catch (e: Exception) {
            playing = false
        }
    }

    fun toggle() {
        if (index < 0) { play(0); return }
        if (mp.isPlaying) { mp.pause(); playing = false }
        else { mp.start(); playing = true }
    }

    LaunchedEffect(playing) {
        while (playing) {
            if (!seeking) pos = mp.currentPosition
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Text(
            "Music Player",
            fontSize = 24.sp,
            modifier = Modifier.padding(16.dp)
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                !granted -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Izin akses musik dibutuhkan")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(perm) }) { Text("Beri izin") }
                }
                songs.isEmpty() -> Text(
                    "Tidak ada lagu ditemukan",
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyColumn {
                    itemsIndexed(songs) { i, s ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { play(i) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                s.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (i == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                s.artist,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    if (index >= 0) songs[index].title else "Pilih lagu",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Slider(
                    value = if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = { seeking = true; pos = (it * dur).toInt() },
                    onValueChangeFinished = { mp.seekTo(pos); seeking = false }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(fmt(pos), fontSize = 12.sp)
                    Text(fmt(dur), fontSize = 12.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { play(index - 1) }) { Text("⏮", fontSize = 28.sp) }
                    FilledTonalButton(onClick = { toggle() }) {
                        Text(if (playing) "Jeda" else "Putar")
                    }
                    TextButton(onClick = { play(index + 1) }) { Text("⏭", fontSize = 28.sp) }
                }
            }
        }
    }
}
