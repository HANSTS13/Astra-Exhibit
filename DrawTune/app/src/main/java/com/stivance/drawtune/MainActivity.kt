
package com.stivance.drawtune

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject


// ============================================================
// MAIN ACTIVITY
// ============================================================

class MainActivity : ComponentActivity() {

    private lateinit var audioEngine: DrawTuneAudioEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioEngine = DrawTuneAudioEngine()

        setContent {
            MaterialTheme {
                DrawTuneApp(
                    audioEngine = audioEngine
                )
            }
        }
    }

    override fun onDestroy() {
        audioEngine.release()
        super.onDestroy()
    }
}


// ============================================================
// DRAWING STROKE
// ============================================================

class DrawingStroke {

    val points = mutableStateListOf<Offset>()
}


// ============================================================
// RECORDED NOTE
// ============================================================

data class RecordedNote(
    val note: String,
    val startTime: Long,
    val duration: Long
)



// ============================================================
// SAVED TUNE DATA
// ============================================================

data class SavedTune(
    val id: Long,
    val name: String,
    val instrument: String,
    val key: String,
    val scale: String,
    val octave: String,
    val tempo: Int,
    val tuning: Int,
    val drawing: List<List<Offset>>,
    val notes: List<RecordedNote>
)

private const val DRAW_TUNE_PREFS = "drawtune_storage"
private const val SAVED_TUNES_KEY = "saved_tunes"

private fun savedTuneToJson(tune: SavedTune): JSONObject {
    val json = JSONObject()

    json.put("id", tune.id)
    json.put("name", tune.name)
    json.put("instrument", tune.instrument)
    json.put("key", tune.key)
    json.put("scale", tune.scale)
    json.put("octave", tune.octave)
    json.put("tempo", tune.tempo)
    json.put("tuning", tune.tuning)

    val strokesArray = JSONArray()
    tune.drawing.forEach { stroke ->
        val strokeArray = JSONArray()
        stroke.forEach { point ->
            val pointObject = JSONObject()
            pointObject.put("x", point.x.toDouble())
            pointObject.put("y", point.y.toDouble())
            strokeArray.put(pointObject)
        }
        strokesArray.put(strokeArray)
    }
    json.put("drawing", strokesArray)

    val notesArray = JSONArray()
    tune.notes.forEach { note ->
        val noteObject = JSONObject()
        noteObject.put("note", note.note)
        noteObject.put("startTime", note.startTime)
        noteObject.put("duration", note.duration)
        notesArray.put(noteObject)
    }
    json.put("notes", notesArray)

    return json
}

private fun savedTuneFromJson(json: JSONObject): SavedTune {
    val drawing = mutableListOf<List<Offset>>()
    val strokesArray = json.optJSONArray("drawing") ?: JSONArray()

    for (i in 0 until strokesArray.length()) {
        val strokeArray = strokesArray.optJSONArray(i) ?: JSONArray()
        val points = mutableListOf<Offset>()

        for (j in 0 until strokeArray.length()) {
            val point = strokeArray.optJSONObject(j) ?: continue
            points.add(
                Offset(
                    point.optDouble("x", 0.0).toFloat(),
                    point.optDouble("y", 0.0).toFloat()
                )
            )
        }

        if (points.isNotEmpty()) {
            drawing.add(points)
        }
    }

    val notes = mutableListOf<RecordedNote>()
    val notesArray = json.optJSONArray("notes") ?: JSONArray()

    for (i in 0 until notesArray.length()) {
        val note = notesArray.optJSONObject(i) ?: continue
        notes.add(
            RecordedNote(
                note = note.optString("note", "C4"),
                startTime = note.optLong("startTime", 0L),
                duration = note.optLong("duration", 50L)
            )
        )
    }

    return SavedTune(
        id = json.optLong("id", System.currentTimeMillis()),
        name = json.optString("name", "Untitled Tune"),
        instrument = json.optString("instrument", "Piano"),
        key = json.optString("key", "C"),
        scale = json.optString("scale", "Major"),
        octave = json.optString("octave", "Middle"),
        tempo = json.optInt("tempo", 120),
        tuning = json.optInt("tuning", 440),
        drawing = drawing,
        notes = notes
    )
}

private fun loadSavedTunes(context: Context): List<SavedTune> {
    return try {
        val prefs = context.getSharedPreferences(
            DRAW_TUNE_PREFS,
            Context.MODE_PRIVATE
        )

        val raw = prefs.getString(
            SAVED_TUNES_KEY,
            "[]"
        ) ?: "[]"

        val array = JSONArray(raw)
        val tunes = mutableListOf<SavedTune>()

        for (i in 0 until array.length()) {
            val objectJson = array.optJSONObject(i) ?: continue
            tunes.add(savedTuneFromJson(objectJson))
        }

        tunes.sortedByDescending { it.id }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveSavedTunes(
    context: Context,
    tunes: List<SavedTune>
) {
    val array = JSONArray()

    tunes.forEach { tune ->
        array.put(savedTuneToJson(tune))
    }

    context.getSharedPreferences(
        DRAW_TUNE_PREFS,
        Context.MODE_PRIVATE
    )
        .edit()
        .putString(
            SAVED_TUNES_KEY,
            array.toString()
        )
        .apply()
}

// ============================================================
// AUDIO ENGINE
// ============================================================

class DrawTuneAudioEngine {

    private val sampleRate = 44100

    private var audioTrack: AudioTrack? = null

    private var currentFrequency = 261.63

    private var currentInstrument = "Piano"

    private var isPlaying = false

    private var audioThread: Thread? = null

    private val lock = Any()


    // ========================================================
    // START AUDIO
    // ========================================================

    fun start() {

        if (isPlaying) {
            return
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = maxOf(
            minBufferSize,
            2048
        )

        val attributes =
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes.USAGE_MEDIA
                )
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_MUSIC
                )
                .build()

        val format =
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
                )
                .build()

        audioTrack = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        isPlaying = true

        audioTrack?.play()

        audioThread = Thread {

            val buffer = ShortArray(1024)

            var phase = 0.0

            var noiseSeed = 123456789L

            while (isPlaying) {

                val frequency: Double
                val instrument: String

                synchronized(lock) {
                    frequency = currentFrequency
                    instrument = currentInstrument
                }

                val phaseIncrement =
                    2.0 * PI * frequency / sampleRate

                for (i in buffer.indices) {

                    val basicWave =
                        sin(phase)

                    val sample =
                        when (instrument) {

                            "Piano" -> {
                                (
                                        0.75 * sin(phase) +
                                                0.18 * sin(phase * 2.0) +
                                                0.07 * sin(phase * 3.0)
                                        ) * 0.25
                            }

                            "Synth" -> {
                                (
                                        0.55 * sin(phase) +
                                                0.25 * sin(phase * 2.0) +
                                                0.15 * sin(phase * 3.0) +
                                                0.05 * sin(phase * 5.0)
                                        ) * 0.28
                            }

                            "Guitar" -> {
                                (
                                        0.80 * sin(phase) +
                                                0.12 * sin(phase * 2.0) +
                                                0.08 * sin(phase * 3.0)
                                        ) * 0.25
                            }

                            "Violin" -> {
                                (
                                        0.55 * sin(phase) +
                                                0.25 * sin(phase * 2.0) +
                                                0.12 * sin(phase * 3.0) +
                                                0.08 * sin(phase * 4.0)
                                        ) * 0.25
                            }

                            "Drum" -> {

                                noiseSeed =
                                    noiseSeed
                                        .shl(13)
                                        .xor(noiseSeed)

                                noiseSeed =
                                    noiseSeed
                                        .shr(17)
                                        .xor(noiseSeed)

                                noiseSeed =
                                    noiseSeed
                                        .shl(5)
                                        .xor(noiseSeed)

                                val noise =
                                    (
                                            noiseSeed and 0xFFFF
                                            ) / 32768.0 - 1.0

                                noise * 0.30
                            }

                            else -> {
                                basicWave * 0.25
                            }
                        }

                    buffer[i] =
                        (
                                sample * Short.MAX_VALUE
                                )
                            .toInt()
                            .coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt()
                            )
                            .toShort()

                    phase += phaseIncrement

                    if (phase >= 2.0 * PI) {
                        phase -= 2.0 * PI
                    }
                }

                audioTrack?.write(
                    buffer,
                    0,
                    buffer.size
                )
            }
        }

        audioThread?.start()
    }


    // ========================================================
    // SET NOTE
    // ========================================================

    fun setNote(note: String) {

        val frequency =
            frequencyFromNote(note)

        synchronized(lock) {
            currentFrequency = frequency
        }
    }


    // ========================================================
    // SET INSTRUMENT
    // ========================================================

    fun setInstrument(instrument: String) {

        synchronized(lock) {
            currentInstrument = instrument
        }
    }


    // ========================================================
    // STOP AUDIO
    // ========================================================

    fun stop() {

        isPlaying = false

        try {
            audioThread?.join(100)
        } catch (_: InterruptedException) {
        }

        audioThread = null

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        audioTrack?.release()

        audioTrack = null
    }


    // ========================================================
    // RELEASE
    // ========================================================

    fun release() {
        stop()
    }
}


// ============================================================
// MAIN DRAW TUNE APP
// ============================================================

@Composable
fun DrawTuneApp(
    audioEngine: DrawTuneAudioEngine
) {

    val context = LocalContext.current

    // ========================================================
    // SAVED TUNES
    // ========================================================

    var savedTunes by remember {
        mutableStateOf(
            loadSavedTunes(context)
        )
    }

    var showLibrary by remember {
        mutableStateOf(false)
    }

    var showSaveDialog by remember {
        mutableStateOf(false)
    }

    var tuneName by remember {
        mutableStateOf("")
    }

    // ========================================================
    // DRAWING DATA
    // ========================================================

    val strokes =
        remember {
            mutableStateListOf<DrawingStroke>()
        }

    // ========================================================
    // RECORDED MUSIC DATA
    // ========================================================

    val recordedNotes =
        remember {
            mutableStateListOf<RecordedNote>()
        }

    // ========================================================
    // UI STATE
    // ========================================================

    var currentNote by remember {
        mutableStateOf("C4")
    }

    var isDrawing by remember {
        mutableStateOf(false)
    }

    var isPlayingTune by remember {
        mutableStateOf(false)
    }

    // ========================================================
    // RECORDING STATE
    // ========================================================

    var recordingStartTime by remember {
        mutableStateOf(0L)
    }

    var currentRecordedNote by remember {
        mutableStateOf<String?>(null)
    }

    var currentNoteStartTime by remember {
        mutableStateOf(0L)
    }

    // When editing a saved tune, newly recorded notes are appended
    // after the existing tune instead of starting at time zero.
    var recordingBaseOffset by remember {
        mutableStateOf(0L)
    }


    // ========================================================
    // MUSIC SETTINGS
    // ========================================================

    var selectedInstrument by remember {
        mutableStateOf("Piano")
    }

    var selectedKey by remember {
        mutableStateOf("C")
    }

    var selectedScale by remember {
        mutableStateOf("Major")
    }

    var selectedOctave by remember {
        mutableStateOf("Middle")
    }

    var tempo by remember {
        mutableStateOf(120)
    }

    var tuning by remember {
        mutableStateOf(440)
    }


    // ========================================================
    // SETTINGS DIALOG
    // ========================================================

    var activeSetting by remember {
        mutableStateOf<String?>(null)
    }


    // ========================================================
    // LATEST SETTINGS FOR POINTER INPUT
    // ========================================================

    val latestInstrument by rememberUpdatedState(
        selectedInstrument
    )

    val latestKey by rememberUpdatedState(
        selectedKey
    )

    val latestScale by rememberUpdatedState(
        selectedScale
    )

    val latestOctave by rememberUpdatedState(
        selectedOctave
    )


    // ========================================================
    // PLAYBACK
    // ========================================================

    LaunchedEffect(isPlayingTune) {

        if (!isPlayingTune) {
            return@LaunchedEffect
        }

        if (recordedNotes.isEmpty()) {

            isPlayingTune = false

            return@LaunchedEffect
        }

        audioEngine.setInstrument(
            selectedInstrument
        )

        var previousStartTime = 0L

        for (recordedNote in recordedNotes) {

            if (!isPlayingTune) {
                break
            }

            // Account for gaps between notes
            val gap =
                recordedNote.startTime -
                        previousStartTime

            if (gap > 0) {

                // A gap in the recorded timeline is a rest. Stop the
                // current tone while waiting for the next note.
                audioEngine.stop()

                val tempoFactor =
                    120.0 / tempo.toDouble()

                val adjustedGap =
                    (
                            gap * tempoFactor
                            ).toLong()
                        .coerceAtLeast(1L)

                delay(adjustedGap)
            }

            if (!isPlayingTune) {
                break
            }

            currentNote =
                recordedNote.note

            audioEngine.setNote(
                recordedNote.note
            )

            audioEngine.start()

            val tempoFactor =
                120.0 / tempo.toDouble()

            val adjustedDuration =
                (
                        recordedNote.duration *
                                tempoFactor
                        ).toLong()
                    .coerceAtLeast(50L)

            delay(adjustedDuration)

            previousStartTime =
                recordedNote.startTime +
                        recordedNote.duration
        }

        audioEngine.stop()

        isPlayingTune = false
    }


    // ========================================================
    // MAIN SURFACE
    // ========================================================

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF7F7FA)
    ) {

        if (showLibrary) {

            TuneLibraryScreen(
                tunes = savedTunes,
                isPlaying = isPlayingTune,
                onBack = {
                    isPlayingTune = false
                    audioEngine.stop()
                    showLibrary = false
                },
                onPlay = { tune ->
                    strokes.clear()
                    tune.drawing.forEach { savedStroke ->
                        val stroke = DrawingStroke()
                        stroke.points.addAll(savedStroke)
                        strokes.add(stroke)
                    }

                    recordedNotes.clear()
                    recordedNotes.addAll(tune.notes)

                    recordingStartTime = 0L
                    currentRecordedNote = null
                    currentNoteStartTime = 0L
                    recordingBaseOffset = 0L

                    selectedInstrument = tune.instrument
                    selectedKey = tune.key
                    selectedScale = tune.scale
                    selectedOctave = tune.octave
                    tempo = tune.tempo
                    tuning = tune.tuning

                    currentNote =
                        tune.notes.firstOrNull()?.note ?: "C4"

                    isPlayingTune = true
                },
                onEdit = { tune ->
                    isPlayingTune = false
                    audioEngine.stop()

                    strokes.clear()
                    tune.drawing.forEach { savedStroke ->
                        val stroke = DrawingStroke()
                        stroke.points.addAll(savedStroke)
                        strokes.add(stroke)
                    }

                    recordedNotes.clear()
                    recordedNotes.addAll(tune.notes)

                    // Reset the recording clock for editing. Any new notes
                    // will be appended after the end of the saved tune.
                    recordingStartTime = 0L
                    currentRecordedNote = null
                    currentNoteStartTime = 0L
                    recordingBaseOffset =
                        tune.notes.maxOfOrNull {
                            it.startTime + it.duration
                        } ?: 0L

                    selectedInstrument = tune.instrument
                    selectedKey = tune.key
                    selectedScale = tune.scale
                    selectedOctave = tune.octave
                    tempo = tune.tempo
                    tuning = tune.tuning

                    currentNote =
                        tune.notes.firstOrNull()?.note ?: "C4"

                    showLibrary = false
                },
                onDelete = { tune ->
                    isPlayingTune = false
                    audioEngine.stop()

                    savedTunes =
                        savedTunes.filterNot {
                            it.id == tune.id
                        }

                    saveSavedTunes(
                        context,
                        savedTunes
                    )
                }
            )

        } else {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {

                // ========================================================
                // HEADER
                // ========================================================

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = "DrawTune",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF202124)
                        )

                        Text(
                            text = "Draw your music",
                            fontSize = 15.sp,
                            color = Color.Gray
                        )
                    }

                    TextButton(
                        onClick = {
                            isPlayingTune = false
                            audioEngine.stop()
                            showLibrary = true
                        }
                    ) {
                        Text(
                            text = "MY TUNES",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6750A4)
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )


                // ========================================================
                // MUSIC INFORMATION
                // ========================================================

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {

                        // ------------------------------------------------
                        // INSTRUMENT
                        // ------------------------------------------------

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {

                            Text(
                                text = "Instrument",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )

                            TextButton(
                                onClick = {
                                    activeSetting = "Instrument"
                                }
                            ) {

                                Text(
                                    text =
                                        when (selectedInstrument) {

                                            "Piano" ->
                                                "🎹 Piano"

                                            "Synth" ->
                                                "🎛️ Synth"

                                            "Guitar" ->
                                                "🎸 Guitar"

                                            "Violin" ->
                                                "🎻 Violin"

                                            "Drum" ->
                                                "🥁 Drum"

                                            else ->
                                                selectedInstrument
                                        },

                                    fontSize = 17.sp,

                                    fontWeight =
                                        FontWeight.SemiBold
                                )
                            }
                        }


                        // ------------------------------------------------
                        // KEY / SCALE
                        // ------------------------------------------------

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {

                            Text(
                                text = "Key / Scale",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )

                            TextButton(
                                onClick = {
                                    activeSetting = "Key"
                                }
                            ) {

                                Text(
                                    text =
                                        "$selectedKey $selectedScale",

                                    fontSize = 17.sp,

                                    fontWeight =
                                        FontWeight.SemiBold
                                )
                            }
                        }


                        // ------------------------------------------------
                        // TEMPO
                        // ------------------------------------------------

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment =
                                Alignment.End
                        ) {

                            Text(
                                text = "Tempo",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )

                            TextButton(
                                onClick = {
                                    activeSetting = "Tempo"
                                }
                            ) {

                                Text(
                                    text = "$tempo BPM",

                                    fontSize = 17.sp,

                                    fontWeight =
                                        FontWeight.SemiBold
                                )
                            }
                        }
                    }


                    // ====================================================
                    // SECOND SETTINGS ROW
                    // ====================================================

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 10.dp
                            ),

                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {

                        TextButton(
                            onClick = {
                                activeSetting = "Scale"
                            }
                        ) {

                            Text(
                                text =
                                    "🎵 Scale: $selectedScale"
                            )
                        }


                        TextButton(
                            onClick = {
                                activeSetting = "Octave"
                            }
                        ) {

                            Text(
                                text =
                                    "🎚️ Octave: $selectedOctave"
                            )
                        }


                        TextButton(
                            onClick = {
                                activeSetting = "Tuning"
                            }
                        ) {

                            Text(
                                text = "🎛️ $tuning Hz"
                            )
                        }
                    }
                }


                Spacer(
                    modifier = Modifier.height(16.dp)
                )


                // ========================================================
                // CANVAS LABEL
                // ========================================================

                Text(
                    text = "DRAWING CANVAS",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )


                // ========================================================
                // DRAWING CANVAS
                // ========================================================

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            Color.White,
                            RoundedCornerShape(18.dp)
                        )
                ) {

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .pointerInput(Unit) {

                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false
                                    )

                                    if (isPlayingTune) {
                                        return@awaitEachGesture
                                    }

                                    isDrawing = true

                                    val clampedX =
                                        down.position.x.coerceIn(
                                            0f,
                                            size.width.toFloat()
                                        )

                                    val clampedY =
                                        down.position.y.coerceIn(
                                            0f,
                                            size.height.toFloat()
                                        )

                                    val clampedPosition =
                                        Offset(clampedX, clampedY)

                                    val stroke = DrawingStroke()
                                    stroke.points.add(clampedPosition)
                                    strokes.add(stroke)

                                    val note =
                                        noteFromPosition(
                                            x = clampedX,
                                            canvasWidth = size.width.toFloat(),
                                            key = latestKey,
                                            scale = latestScale,
                                            octave = latestOctave
                                        )

                                    currentNote = note

                                    val now = System.currentTimeMillis()
                                    if (recordingStartTime == 0L) {
                                        recordingStartTime = now
                                    }

                                    currentRecordedNote = note
                                    currentNoteStartTime = now

                                    audioEngine.setInstrument(latestInstrument)
                                    audioEngine.setNote(note)
                                    audioEngine.start()

                                    var finished = false

                                    try {
                                        while (!finished) {
                                            val event = awaitPointerEvent()
                                            val change =
                                                event.changes.firstOrNull {
                                                    it.id == down.id
                                                } ?: break

                                            if (!change.pressed) {
                                                val releaseNow =
                                                    System.currentTimeMillis()

                                                if (currentRecordedNote != null) {
                                                    val duration =
                                                        (releaseNow - currentNoteStartTime)
                                                            .coerceAtLeast(50L)

                                                    val relativeStart =
                                                        (currentNoteStartTime - recordingStartTime)
                                                            .coerceAtLeast(0L)

                                                    recordedNotes.add(
                                                        RecordedNote(
                                                            note = currentRecordedNote!!,
                                                            startTime = relativeStart,
                                                            duration = duration
                                                        )
                                                    )
                                                }

                                                currentRecordedNote = null
                                                isDrawing = false
                                                audioEngine.stop()
                                                finished = true
                                            } else {
                                                change.consume()

                                                val clampedMoveX =
                                                    change.position.x.coerceIn(
                                                        0f,
                                                        size.width.toFloat()
                                                    )

                                                val clampedMoveY =
                                                    change.position.y.coerceIn(
                                                        0f,
                                                        size.height.toFloat()
                                                    )

                                                val movePosition =
                                                    Offset(clampedMoveX, clampedMoveY)

                                                strokes.lastOrNull()?.points?.add(movePosition)

                                                val movedNote =
                                                    noteFromPosition(
                                                        x = clampedMoveX,
                                                        canvasWidth = size.width.toFloat(),
                                                        key = latestKey,
                                                        scale = latestScale,
                                                        octave = latestOctave
                                                    )

                                                currentNote = movedNote
                                                audioEngine.setNote(movedNote)
                                                audioEngine.setInstrument(latestInstrument)

                                                if (
                                                    currentRecordedNote != null &&
                                                    movedNote != currentRecordedNote
                                                ) {
                                                    val noteNow = System.currentTimeMillis()
                                                    val duration =
                                                        (noteNow - currentNoteStartTime)
                                                            .coerceAtLeast(20L)
                                                    val relativeStart =
                                                        (currentNoteStartTime - recordingStartTime)
                                                            .coerceAtLeast(0L)

                                                    recordedNotes.add(
                                                        RecordedNote(
                                                            note = currentRecordedNote!!,
                                                            startTime = relativeStart,
                                                            duration = duration
                                                        )
                                                    )

                                                    currentRecordedNote = movedNote
                                                    currentNoteStartTime = noteNow
                                                }
                                            }
                                        }
                                    } catch (_: CancellationException) {
                                        currentRecordedNote = null
                                        isDrawing = false
                                        audioEngine.stop()
                                    }
                                }
                            }
                    ) {


                        // ====================================================
                        // MUSICAL GUIDE LINES
                        // ====================================================

                        val guideLines = 8

                        for (i in 0..guideLines) {

                            val y =
                                size.height *
                                        i.toFloat() /
                                        guideLines.toFloat()

                            drawLine(
                                color =
                                    Color(0xFFE8E8ED),

                                start =
                                    Offset(
                                        0f,
                                        y
                                    ),

                                end =
                                    Offset(
                                        size.width,
                                        y
                                    ),

                                strokeWidth = 1f
                            )
                        }


                        // ====================================================
                        // DRAW USER STROKES
                        // ====================================================

                        for (stroke in strokes) {

                            if (stroke.points.isEmpty()) {
                                continue
                            }


                            // ----------------------------------------------
                            // SINGLE POINT
                            // ----------------------------------------------

                            if (stroke.points.size == 1) {

                                drawCircle(
                                    color =
                                        Color(0xFF6750A4),

                                    radius = 4f,

                                    center =
                                        stroke.points[0]
                                )

                                continue
                            }


                            // ----------------------------------------------
                            // CREATE PATH
                            // ----------------------------------------------

                            val path =
                                Path()

                            path.moveTo(
                                stroke.points[0].x,
                                stroke.points[0].y
                            )

                            for (
                            i in 1 until stroke.points.size
                            ) {

                                path.lineTo(
                                    stroke.points[i].x,
                                    stroke.points[i].y
                                )
                            }


                            // ----------------------------------------------
                            // DRAW PATH
                            // ----------------------------------------------

                            drawPath(
                                path = path,

                                color =
                                    Color(0xFF6750A4),

                                style =
                                    Stroke(
                                        width = 6f,

                                        cap =
                                            StrokeCap.Round,

                                        join =
                                            StrokeJoin.Round
                                    )
                            )
                        }
                    }


                    // ========================================================
                    // EMPTY CANVAS MESSAGE
                    // ========================================================

                    if (
                        !isDrawing &&
                        strokes.isEmpty()
                    ) {

                        Text(
                            text =
                                "Touch and draw here",

                            modifier =
                                Modifier.align(
                                    Alignment.Center
                                ),

                            color =
                                Color.LightGray,

                            fontSize = 17.sp
                        )
                    }
                }


                Spacer(
                    modifier = Modifier.height(12.dp)
                )


                // ========================================================
                // CURRENT NOTE
                // ========================================================

                Card(
                    modifier =
                        Modifier.fillMaxWidth(),

                    shape =
                        RoundedCornerShape(14.dp),

                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                Color(0xFFEDE7F6)
                        )
                ) {

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),

                        verticalAlignment =
                            Alignment.CenterVertically,

                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {

                        Text(
                            text =
                                "Current Note",

                            fontSize =
                                15.sp
                        )

                        Text(
                            text =
                                currentNote,

                            fontSize =
                                28.sp,

                            fontWeight =
                                FontWeight.Bold,

                            color =
                                Color(0xFF6750A4)
                        )
                    }
                }


                Spacer(
                    modifier = Modifier.height(12.dp)
                )


                // ========================================================
                // BUTTONS
                // ========================================================

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {


                    // ====================================================
                    // CLEAR
                    // ====================================================

                    Button(
                        onClick = {

                            isPlayingTune = false

                            strokes.clear()

                            recordedNotes.clear()

                            recordingStartTime =
                                0L

                            recordingBaseOffset =
                                0L

                            currentRecordedNote =
                                null

                            currentNoteStartTime =
                                0L

                            currentNote =
                                "C4"

                            isDrawing =
                                false

                            audioEngine.stop()
                        },

                        modifier =
                            Modifier
                                .weight(1f)
                                .height(52.dp),

                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    Color(0xFF444444)
                            )
                    ) {

                        Text(
                            text =
                                "CLEAR",

                            fontWeight =
                                FontWeight.Bold
                        )
                    }


                    // ====================================================
                    // PLAY
                    // ====================================================

                    Button(
                        onClick = {

                            if (isPlayingTune) {

                                isPlayingTune =
                                    false

                                audioEngine.stop()

                            } else if (
                                recordedNotes.isNotEmpty()
                            ) {

                                isPlayingTune =
                                    true
                            }
                        },

                        modifier =
                            Modifier
                                .weight(1f)
                                .height(52.dp)
                    ) {

                        Text(
                            text =
                                if (isPlayingTune) {
                                    "■ STOP"
                                } else {
                                    "▶ PLAY"
                                },

                            fontWeight =
                                FontWeight.Bold
                        )
                    }


                    // ====================================================
                    // SAVE
                    // ====================================================

                    Button(
                        onClick = {
                            if (recordedNotes.isNotEmpty()) {
                                tuneName = ""
                                showSaveDialog = true
                            }
                        },

                        modifier =
                            Modifier
                                .weight(1f)
                                .height(52.dp)
                    ) {

                        Text(
                            text =
                                "💾 SAVE",

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }
        }


        // ============================================================
        // SAVE DIALOG
        // ============================================================

        if (showSaveDialog) {

            AlertDialog(
                onDismissRequest = {
                    showSaveDialog = false
                },

                title = {
                    Text(
                        text = "💾 Save Tune",
                        fontWeight = FontWeight.Bold
                    )
                },

                text = {
                    OutlinedTextField(
                        value = tuneName,
                        onValueChange = {
                            tuneName = it
                        },
                        label = {
                            Text("Tune name")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },

                confirmButton = {

                    TextButton(
                        enabled =
                            tuneName.trim().isNotEmpty() &&
                                    recordedNotes.isNotEmpty(),

                        onClick = {

                            val newTune =
                                SavedTune(
                                    id = System.currentTimeMillis(),
                                    name = tuneName.trim(),
                                    instrument = selectedInstrument,
                                    key = selectedKey,
                                    scale = selectedScale,
                                    octave = selectedOctave,
                                    tempo = tempo,
                                    tuning = tuning,
                                    drawing =
                                        strokes.map { stroke ->
                                            stroke.points.toList()
                                        },
                                    notes =
                                        recordedNotes.toList()
                                )

                            savedTunes =
                                listOf(
                                    newTune
                                ) + savedTunes

                            saveSavedTunes(
                                context,
                                savedTunes
                            )

                            showSaveDialog = false
                            tuneName = ""
                        }
                    ) {
                        Text("SAVE")
                    }
                },

                dismissButton = {

                    TextButton(
                        onClick = {
                            showSaveDialog = false
                        }
                    ) {
                        Text("CANCEL")
                    }
                }
            )
        }


        // ============================================================
        // SETTINGS DIALOGS
        // ============================================================

        when (activeSetting) {

            // ========================================================
            // INSTRUMENT
            // ========================================================

            "Instrument" -> {

                SelectionDialog(
                    title =
                        "🎹 Select Instrument",

                    options =
                        listOf(
                            "Piano",
                            "Synth",
                            "Guitar",
                            "Violin",
                            "Drum"
                        ),

                    selected =
                        selectedInstrument,

                    onSelected = {

                        selectedInstrument =
                            it

                        audioEngine.setInstrument(
                            it
                        )

                        activeSetting =
                            null
                    },

                    onDismiss = {
                        activeSetting =
                            null
                    }
                )
            }


            // ========================================================
            // KEY
            // ========================================================

            "Key" -> {

                SelectionDialog(
                    title =
                        "🎼 Select Key",

                    options =
                        listOf(
                            "C",
                            "D",
                            "E",
                            "F",
                            "G",
                            "A",
                            "B"
                        ),

                    selected =
                        selectedKey,

                    onSelected = {

                        selectedKey =
                            it

                        activeSetting =
                            null
                    },

                    onDismiss = {
                        activeSetting =
                            null
                    }
                )
            }


            // ========================================================
            // SCALE
            // ========================================================

            "Scale" -> {

                SelectionDialog(
                    title =
                        "🎵 Select Scale",

                    options =
                        listOf(
                            "Major",
                            "Minor",
                            "Pentatonic",
                            "Blues",
                            "Chromatic"
                        ),

                    selected =
                        selectedScale,

                    onSelected = {

                        selectedScale =
                            it

                        activeSetting =
                            null
                    },

                    onDismiss = {
                        activeSetting =
                            null
                    }
                )
            }


            // ========================================================
            // OCTAVE
            // ========================================================

            "Octave" -> {

                SelectionDialog(
                    title =
                        "🎚️ Select Octave",

                    options =
                        listOf(
                            "Low",
                            "Middle",
                            "High"
                        ),

                    selected =
                        selectedOctave,

                    onSelected = {

                        selectedOctave =
                            it

                        activeSetting =
                            null
                    },

                    onDismiss = {
                        activeSetting =
                            null
                    }
                )
            }


            // ========================================================
            // TEMPO
            // ========================================================

            "Tempo" -> {

                TempoDialog(
                    tempo =
                        tempo,

                    onTempoChanged = {

                        tempo =
                            it
                    },

                    onDismiss = {

                        activeSetting =
                            null
                    }
                )
            }


            // ========================================================
            // TUNING
            // ========================================================

            "Tuning" -> {

                SelectionDialog(
                    title =
                        "🎛️ Select Tuning",

                    options =
                        listOf(
                            "432 Hz",
                            "435 Hz",
                            "440 Hz"
                        ),

                    selected =
                        "$tuning Hz",

                    onSelected = {

                        tuning =
                            it
                                .removeSuffix(
                                    " Hz"
                                )
                                .toIntOrNull()
                                ?: 440

                        activeSetting =
                            null
                    },

                    onDismiss = {

                        activeSetting =
                            null
                    }
                )
            }
        }
    }



}


// ============================================================
// MY TUNES SCREEN
// ============================================================

@Composable
fun TuneLibraryScreen(
    tunes: List<SavedTune>,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlay: (SavedTune) -> Unit,
    onEdit: (SavedTune) -> Unit,
    onDelete: (SavedTune) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack
            ) {
                Text(
                    text = "← BACK",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6750A4)
                )
            }

            Text(
                text = "MY TUNES",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        if (tunes.isEmpty()) {

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🎵",
                        fontSize = 40.sp
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text = "No saved tunes yet",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Draw something and press SAVE",
                        color = Color.Gray
                    )
                }
            }

        } else {

            LazyColumn(
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = tunes,
                    key = { it.id }
                ) { tune ->

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    Color.White
                            )
                    ) {

                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                        ) {

                            Text(
                                text = tune.name,
                                fontSize = 20.sp,
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Spacer(
                                modifier =
                                    Modifier.height(4.dp)
                            )

                            Text(
                                text =
                                    "${tune.instrument} • " +
                                            "${tune.key} ${tune.scale} • " +
                                            "${tune.tempo} BPM",
                                color = Color.Gray
                            )

                            Text(
                                text =
                                    "${tune.notes.size} notes • " +
                                            "${tune.tuning} Hz",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )

                            Spacer(
                                modifier =
                                    Modifier.height(10.dp)
                            )

                            Row(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {

                                Button(
                                    onClick = {
                                        onPlay(tune)
                                    },
                                    modifier =
                                        Modifier.weight(1f),
                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor =
                                                Color(0xFF6750A4)
                                        )
                                ) {
                                    Text(
                                        text =
                                            if (isPlaying) {
                                                "■ STOP"
                                            } else {
                                                "▶ PLAY"
                                            }
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        onEdit(tune)
                                    },
                                    modifier =
                                        Modifier.weight(1f)
                                ) {
                                    Text("✏️ EDIT")
                                }

                                TextButton(
                                    onClick = {
                                        onDelete(tune)
                                    },
                                    modifier =
                                        Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "🗑️",
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// ============================================================
// SELECTION DIALOG
// ============================================================

@Composable
fun SelectionDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {

    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {

            Text(
                text =
                    title,

                fontWeight =
                    FontWeight.Bold
            )
        },

        text = {

            Column {

                options.forEach { option ->

                    TextButton(
                        onClick = {
                            onSelected(
                                option
                            )
                        },

                        modifier =
                            Modifier.fillMaxWidth()
                    ) {

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Text(
                                text =
                                    option,

                                fontSize =
                                    17.sp
                            )

                            if (
                                option ==
                                selected
                            ) {

                                Text(
                                    text =
                                        "✓",

                                    color =
                                        Color(
                                            0xFF6750A4
                                        ),

                                    fontWeight =
                                        FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick =
                    onDismiss
            ) {

                Text(
                    text =
                        "CLOSE"
                )
            }
        }
    )
}


// ============================================================
// TEMPO DIALOG
// ============================================================

@Composable
fun TempoDialog(
    tempo: Int,
    onTempoChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {

    var sliderValue by remember(tempo) {
        mutableStateOf(
            tempo.toFloat()
        )
    }

    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {

            Text(
                text =
                    "⏱️ Tempo",

                fontWeight =
                    FontWeight.Bold
            )
        },

        text = {

            Column {

                Text(
                    text =
                        "${sliderValue.toInt()} BPM",

                    fontSize =
                        28.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color(0xFF6750A4)
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Slider(
                    value =
                        sliderValue,

                    onValueChange = {

                        sliderValue =
                            it

                        onTempoChanged(
                            it.toInt()
                        )
                    },

                    valueRange =
                        60f..180f,

                    steps =
                        11
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {

                    Text(
                        text =
                            "60",

                        color =
                            Color.Gray
                    )

                    Text(
                        text =
                            "120",

                        color =
                            Color.Gray
                    )

                    Text(
                        text =
                            "180",

                        color =
                            Color.Gray
                    )
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick =
                    onDismiss
            ) {

                Text(
                    text =
                        "DONE"
                )
            }
        }
    )
}


// ============================================================
// NOTE CALCULATION
// ============================================================

fun noteFromPosition(
    x: Float,
    canvasWidth: Float,
    key: String,
    scale: String,
    octave: String
): String {

    if (canvasWidth <= 0f) {
        return "C4"
    }


    // ========================================================
    // SCALE INTERVALS
    // ========================================================

    val intervals =
        when (scale) {

            "Major" ->
                listOf(
                    0,
                    2,
                    4,
                    5,
                    7,
                    9,
                    11
                )

            "Minor" ->
                listOf(
                    0,
                    2,
                    3,
                    5,
                    7,
                    8,
                    10
                )

            "Pentatonic" ->
                listOf(
                    0,
                    2,
                    4,
                    7,
                    9
                )

            "Blues" ->
                listOf(
                    0,
                    3,
                    5,
                    6,
                    7,
                    10
                )

            "Chromatic" ->
                (0..11).toList()

            else ->
                listOf(
                    0,
                    2,
                    4,
                    5,
                    7,
                    9,
                    11
                )
        }


    // ========================================================
    // KEY
    // ========================================================

    val keySemitone =
        when (key) {

            "C" -> 0
            "D" -> 2
            "E" -> 4
            "F" -> 5
            "G" -> 7
            "A" -> 9
            "B" -> 11

            else -> 0
        }


    // ========================================================
    // OCTAVE
    // ========================================================

    val baseOctave =
        when (octave) {

            "Low" ->
                3

            "Middle" ->
                4

            "High" ->
                5

            else ->
                4
        }


    // ========================================================
    // X POSITION
    // ========================================================

    val normalizedX =
        (x / canvasWidth)
            .coerceIn(
                0f,
                0.999999f
            )

    // Eight zones across canvas
    val noteIndex =
        (normalizedX * 8)
            .toInt()
            .coerceIn(
                0,
                7
            )


    // ========================================================
    // SCALE POSITION
    // ========================================================

    val scaleIndex =
        noteIndex %
                intervals.size

    val octaveOffset =
        noteIndex /
                intervals.size

    val semitone =
        keySemitone +
                intervals[
                    scaleIndex
                ]

    val totalSemitones =
        semitone % 12

    val octaveCarry =
        semitone / 12

    val finalOctave =
        baseOctave +
                octaveOffset +
                octaveCarry


    val noteNames =
        listOf(
            "C",
            "C#",
            "D",
            "D#",
            "E",
            "F",
            "F#",
            "G",
            "G#",
            "A",
            "A#",
            "B"
        )


    return "${
        noteNames[
            totalSemitones
        ]
    }$finalOctave"
}


// ============================================================
// NOTE → FREQUENCY
// ============================================================

fun frequencyFromNote(
    note: String
): Double {

    val noteNames =
        mapOf(
            "C" to 0,
            "C#" to 1,
            "D" to 2,
            "D#" to 3,
            "E" to 4,
            "F" to 5,
            "F#" to 6,
            "G" to 7,
            "G#" to 8,
            "A" to 9,
            "A#" to 10,
            "B" to 11
        )


    if (note.length < 2) {
        return 261.63
    }


    val noteName =
        if (
            note.length >= 2 &&
            note[1] == '#'
        ) {

            note.substring(
                0,
                2
            )

        } else {

            note.substring(
                0,
                1
            )
        }


    val octaveStart =
        if (
            note.length >= 2 &&
            note[1] == '#'
        ) {

            2

        } else {

            1
        }


    val octave =
        note.substring(
            octaveStart
        )
            .toIntOrNull()
            ?: 4


    val semitone =
        noteNames[
            noteName
        ]
            ?: 0


    // MIDI note number
    val midi =
        (
                octave + 1
                ) * 12 +
                semitone


    // A4 = MIDI 69
    return 440.0 *
            Math.pow(
                2.0,
                (
                        midi - 69
                        ) / 12.0
            )
}
