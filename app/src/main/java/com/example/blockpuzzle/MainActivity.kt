package com.example.blockpuzzle

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlin.math.*
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var game: GameView
    private val prefs by lazy { getSharedPreferences("block_puzzle", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = GameView(this, prefs)
        setContentView(game)
    }
    override fun onBackPressed() { showMenu() }
    fun showMenu() {
        AlertDialog.Builder(this).setTitle("Block Puzzle")
            .setItems(arrayOf("Resume game", "Settings", "New game", "How to play")) { _, which ->
                when (which) {
                    1 -> showSettings()
                    2 -> game.newGame()
                    3 -> AlertDialog.Builder(this).setTitle("How to play")
                        .setMessage("Drag one of the three pieces onto the 10×10 board. Complete a full row or column to clear it and earn points. Place all three pieces to receive a new set. The game ends when none of your pieces can fit.")
                        .setPositiveButton("Got it", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }
    private fun showSettings() {
        val labels = arrayOf("Music", "Sound effects", "Vibration", "Assist mode (easy pieces)")
        val keys = arrayOf("music", "sfx", "vibration", "assist")
        val checks = BooleanArray(4) { prefs.getBoolean(keys[it], it != 3) }
        AlertDialog.Builder(this).setTitle("Settings")
            .setMultiChoiceItems(labels, checks) { _, which, checked ->
                prefs.edit().putBoolean(keys[which], checked).apply()
                game.settingsChanged()
            }
            .setPositiveButton("Done", null).show()
    }
    inner class GameView(context: Context, private val p: android.content.SharedPreferences) : View(context) {
        private val n = 10
        private val board = Array(n) { IntArray(n) }
        private val colors = intArrayOf(Color.rgb(75, 190, 245), Color.rgb(255, 179, 71), Color.rgb(160, 125, 255),
            Color.rgb(75, 215, 160), Color.rgb(255, 112, 145), Color.rgb(245, 220, 90))
        private val shapes = listOf(
            listOf(0 to 0), listOf(0 to 0, 0 to 1), listOf(0 to 0, 1 to 0),
            listOf(0 to 0, 0 to 1, 0 to 2), listOf(0 to 0, 1 to 0, 2 to 0),
            listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1),
            listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1),
            listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2),
            listOf(0 to 0, 0 to 1, 0 to 2, 1 to 1),
            listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),
            listOf(0 to 1, 1 to 1, 2 to 0, 2 to 1),
            listOf(0 to 0, 0 to 1, 1 to 1, 1 to 2),
            listOf(0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1),
            listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1)
        )
        private data class Piece(val cells: List<Pair<Int,Int>>, val color: Int)
        private var pieces = mutableListOf<Piece>()
        private var score = 0
        private var best = p.getInt("best", 0)
        private var combo = 0
        private var selected = -1
        private var dragX = 0f
        private var dragY = 0f
        private var downX = 0f
        private var downY = 0f
        private var boardLeft = 0f
        private var boardTop = 0f
        private var cell = 0f
        private var trayTop = 0f
        private val paint = Paint(3)
        private val bg = Paint().apply { color = Color.rgb(15, 19, 36) }
        private val particles = mutableListOf<Particle>()
        private data class Particle(var x:Float,var y:Float,var vx:Float,var vy:Float,var life:Int,var color:Int)
        private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        private val music = MusicLoop()
        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null); refill(); settingsChanged() }
        fun settingsChanged() {
            if (p.getBoolean("music", true)) music.start() else music.stop()
            invalidate()
        }
        fun newGame() {
            for (r in 0 until n) board[r].fill(0)
            score = 0; combo = 0; selected = -1; particles.clear(); refill(); invalidate()
        }
        private fun refill() {
            pieces.clear()
            repeat(3) { pieces.add(makePiece()) }
        }
        private fun makePiece(): Piece {
            val assist = p.getBoolean("assist", false)
            val candidates = if (assist) shapes.filter { s -> hasFit(s) } else shapes
            val shape = (candidates.ifEmpty { listOf(shapes.first()) }).random()
            return Piece(shape, colors.random())
        }
        private fun hasFit(shape: List<Pair<Int,Int>>): Boolean {
            for (r in 0 until n) for (c in 0 until n) if (canPlace(shape,r,c)) return true
            return false
        }
        private fun canPlace(shape: List<Pair<Int,Int>>, r:Int,c:Int):Boolean =
            shape.all { (y,x) -> r+y in 0 until n && c+x in 0 until n && board[r+y][c+x] == 0 }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),bg)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create("sans-serif",Typeface.BOLD)
            paint.textSize = width * .075f
            canvas.drawText("BLOCK PUZZLE", width/2f, height*.075f, paint)
            paint.textSize = width*.035f; paint.color = Color.LTGRAY
            canvas.drawText("SCORE  $score       BEST  $best",width/2f,height*.125f,paint)
            paint.color = Color.rgb(37,43,67); paint.textAlign = Paint.Align.LEFT
            canvas.drawRoundRect(width*.07f,height*.15f,width*.93f,height*.205f,18f,18f,paint)
            paint.color = Color.WHITE; paint.textSize=width*.032f
            canvas.drawText("☰  MENU",width*.10f,height*.187f,paint)
            val size = min(width*.086f, (height*.49f)/10f)
            cell = size
            boardLeft = (width-size*10)/2f
            boardTop = height*.235f
            for (r in 0 until n) for (c in 0 until n) {
                val x=boardLeft+c*size; val y=boardTop+r*size
                paint.color = if (board[r][c]==0) Color.rgb(34,41,65) else board[r][c]
                canvas.drawRoundRect(x+2,y+2,x+size-2,y+size-2,size*.16f,size*.16f,paint)
                if (board[r][c]!=0) {
                    paint.color=Color.argb(65,255,255,255)
                    canvas.drawRoundRect(x+size*.12f,y+size*.09f,x+size*.88f,y+size*.25f,size*.08f,size*.08f,paint)
                }
            }
            trayTop=boardTop+size*10+height*.065f
            paint.color=Color.WHITE; paint.textSize=width*.035f
            canvas.drawText("CHOOSE A BLOCK",width/2f,trayTop-height*.022f,paint)
            val slotW=width/3f
            pieces.forEachIndexed { i,piece ->
                if (i==selected) return@forEachIndexed
                drawPiece(canvas,piece,slotW*(i+.5f),trayTop+height*.075f, min(width*.047f,height*.025f))
            }
            if (selected >= 0 && selected < pieces.size) {
                drawPiece(canvas, pieces[selected], dragX, dragY-height*.075f, min(cell*.72f, height*.045f))
            }
            paint.color=Color.rgb(117,130,164); paint.textSize=width*.027f
            canvas.drawText("Clear rows + columns • Build combos • Beat your best",width/2f,height*.96f,paint)
            val it=particles.iterator()
            while(it.hasNext()) {
                val q=it.next(); paint.color=q.color; paint.alpha=(255*q.life/24).coerceIn(0,255)
                canvas.drawCircle(q.x,q.y,cell*.07f,paint); paint.alpha=255
                q.x+=q.vx; q.y+=q.vy; q.vy+=.12f; q.life--
                if(q.life<=0) it.remove()
            }
            if(particles.isNotEmpty()) postInvalidateDelayed(16)
            if(p.getBoolean("assist",false)) {
                paint.color=Color.rgb(110,225,170); paint.textSize=width*.025f
                canvas.drawText("ASSIST ON",width*.87f,height*.187f,paint)
            }
        }
        private fun drawPiece(c:Canvas,p:Piece,cx:Float,cy:Float,s:Float) {
            val maxX=(p.cells.maxOf{it.second}+1); val maxY=(p.cells.maxOf{it.first}+1)
            val ox=cx-maxX*s/2; val oy=cy-maxY*s/2
            p.cells.forEach { (r,col) ->
                paint.color=p.color
                c.drawRoundRect(ox+col*s+1,oy+r*s+1,ox+(col+1)*s-1,oy+(r+1)*s-1,s*.17f,s*.17f,paint)
                paint.color=Color.argb(60,255,255,255)
                c.drawRoundRect(ox+col*s+s*.12f,oy+r*s+s*.08f,ox+(col+1)*s-s*.12f,oy+r*s+s*.23f,s*.06f,s*.06f,paint)
            }
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; dragX=e.x; dragY=e.y
                    if(e.y in height*.145f..height*.21f && e.x<width*.35f) { (context as MainActivity).showMenu(); return true }
                    if(e.y>trayTop-height*.01f) {
                        val idx=(e.x/(width/3f)).toInt().coerceIn(0,2)
                        if(idx<pieces.size) { selected=idx; return true }
                    }
                }
                MotionEvent.ACTION_MOVE -> { if(selected>=0) { dragX=e.x; dragY=e.y; invalidate() } }
                MotionEvent.ACTION_UP -> {
                    if(selected>=0) {
                        val c=((e.x-boardLeft)/cell).toInt()
                        val r=((e.y-boardTop)/cell).toInt()
                        val piece=pieces[selected]
                        if(e.y>=boardTop && e.y<boardTop+cell*10 && canPlace(piece.cells,r,c)) place(piece,r,c)
                        selected=-1
                    }
                    invalidate()
                }
            }
            return true
        }
        private fun place(piece:Piece,r:Int,c:Int) {
            piece.cells.forEach { (y,x) -> board[r+y][c+x]=piece.color }
            score+=piece.cells.size*2
            val rows=(0 until n).filter { rr -> board[rr].all{it!=0} }
            val cols=(0 until n).filter { cc -> (0 until n).all{board[it][cc]!=0} }
            if(rows.isNotEmpty() || cols.isNotEmpty()) {
                val cleared=mutableSetOf<Pair<Int,Int>>()
                rows.forEach { rr -> for(cc in 0 until n) cleared.add(rr to cc) }
                cols.forEach { cc -> for(rr in 0 until n) cleared.add(rr to cc) }
                cleared.forEach { (rr,cc) ->
                    val x=boardLeft+(cc+.5f)*cell; val y=boardTop+(rr+.5f)*cell
                    repeat(3) { particles.add(Particle(x,y,Random.nextFloat()*6-3,Random.nextFloat()*-6-1,24,board[rr][cc])) }
                    board[rr][cc]=0
                }
                combo++; score+=cleared.size*10+rows.size*50+cols.size*50+(combo-1)*25
                sound(true)
            } else combo=0
            if(p.getBoolean("vibration",true)) {
                if(android.os.Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createOneShot(if(rows.isNotEmpty()||cols.isNotEmpty())55 else 18, if(rows.isNotEmpty()||cols.isNotEmpty())180 else 70))
                else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(18)
                }
            }
            sound(false)
            if(score>best) { best=score; p.edit().putInt("best",best).apply() }
            pieces.removeAt(selected)
            if(pieces.isEmpty()) refill()
            if(pieces.all { !hasFit(it.cells) }) {
                if(p.getBoolean("assist",false)) {
                    board.forEach { row -> row.fill(0) }
                    pieces.clear(); refill()
                } else {
                    AlertDialog.Builder(context).setTitle("Game over")
                        .setMessage("Score: $score\nBest: $best")
                        .setPositiveButton("Play again") { _,_ -> newGame() }
                        .setNegativeButton("Close",null).show()
                }
            }
            invalidate()
        }
        private fun sound(clear:Boolean) {
            if(!p.getBoolean("sfx",true)) return
            try {
                val tg=android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC,65)
                tg.startTone(if(clear) android.media.ToneGenerator.TONE_PROP_ACK else android.media.ToneGenerator.TONE_PROP_BEEP,if(clear)150 else 45)
                postDelayed({tg.release()},220)
            } catch(_:Exception) {}
        }
    }
    private class MusicLoop {
        @Volatile private var running=false
        private var thread:Thread?=null
        fun start() {
            if(running) return
            running=true
            thread=Thread {
                val rate=22050
                val min=AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                val track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(max(min,4096)).setTransferMode(AudioTrack.MODE_STREAM).build()
                try {
                    track.play()
                    val notes=intArrayOf(392,494,587,494,440,523,659,523)
                    var idx=0
                    while(running) {
                        val freq=notes[idx++%notes.size]
                        val samples=ByteArray(rate/3*2)
                        for(i in samples.indices step 2) {
                            val t=i/2.0/rate
                            val v=(sin(2*PI*freq*t)*1800*(1.0-t*3).coerceAtLeast(0.0)).toInt()
                            samples[i]=(v and 255).toByte(); samples[i+1]=(v shr 8).toByte()
                        }
                        track.write(samples,0,samples.size)
                    }
                } catch(_:Exception) {} finally { try { track.stop(); track.release() } catch(_:Exception){} }
            }.also { it.isDaemon=true; it.start() }
        }
        fun stop() { running=false }
    }
}
