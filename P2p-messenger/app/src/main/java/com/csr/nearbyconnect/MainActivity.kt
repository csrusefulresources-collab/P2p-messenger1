package com.csr.nearbyconnect

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), BluetoothPeer.Listener {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: BluetoothAdapter
    private var peer: BluetoothPeer? = null
    private val found = linkedMapOf<String, BluetoothDevice>()
    private var username = ""
    private var tab = "Chats"
    private var recorder: MediaRecorder? = null
    private var noteFile: File? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val callRecording = AtomicBoolean(false)
    private val sampleRate = 16000

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { found[it.address] = it; if (tab == "Chats") showChats() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> setStatus("Scan finished. Select a device to connect.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(BluetoothManager::class.java)
        adapter = manager.adapter ?: run { Toast.makeText(this, "Bluetooth is not supported", Toast.LENGTH_LONG).show(); finish(); return }
        username = getPreferences(MODE_PRIVATE).getString("username", "") ?: ""
        peer = BluetoothPeer(adapter, this)
        val discoveryFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(discoveryReceiver, discoveryFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(discoveryReceiver, discoveryFilter)
        }
        buildShell()
        if (username.isBlank()) showSetup() else showChats()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF4F7F6.toInt()) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 18, 20, 14); setBackgroundColor(0xFF075E54.toInt()) }
        header.addView(TextView(this).apply { text = "Nearby Connect"; textSize = 23f; setTextColor(-1); contentDescription = "Nearby Connect" })
        status = TextView(this).apply { text = "Private Bluetooth messaging"; textSize = 13f; setTextColor(0xFFE0F2EF.toInt()); contentDescription = "Connection status" }
        header.addView(status)
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(-1); gravity = Gravity.CENTER }
        listOf("Chats", "Calls", "Settings").forEach { name ->
            nav.addView(Button(this).apply { text = name; contentDescription = "$name tab"; setOnClickListener { tab = name; when (name) { "Chats" -> showChats(); "Calls" -> showCalls(); else -> showSettings() } } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root)
    }

    private fun heading(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 20f; setTextColor(0xFF173C36.toInt()); setPadding(0, 4, 0, 12) }) }
    private fun label(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 15f; setTextColor(0xFF263B37.toInt()); setPadding(0, 8, 0, 8) }) }
    private fun button(text: String, action: () -> Unit) { content.addView(Button(this).apply { this.text = text; contentDescription = text; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 }) }
    private fun edit(hint: String): EditText = EditText(this).apply { this.hint = hint; contentDescription = hint; textSize = 16f; singleLine = true }
    private fun showSetup() {
        content.removeAllViews(); heading("Welcome")
        label("Create a username so nearby contacts can recognize you.")
        val name = edit("Username")
        content.addView(name)
        button("Save Username") { val value = name.text.toString().trim(); if (value.length < 3) toast("Username must be at least 3 characters") else { username = value; getPreferences(MODE_PRIVATE).edit().putString("username", value).apply(); showChats() } }
        button("Skip Permissions for Now") { showChats() }
        label("You can grant permissions later in Settings. Bluetooth, microphone and SMS features will remain unavailable until their permissions are granted.")
        button("Grant Permissions") { requestAppPermissions() }
    }

    private fun showChats() {
        content.removeAllViews(); heading("Chats")
        label("Your username: ${if (username.isBlank()) "Not set" else username}")
        button("Find Nearby Devices") { startDiscovery() }
        button("Make This Phone Discoverable") { try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) } catch (_: Exception) { toast("Could not open Bluetooth visibility") } }
        button("Wait for Incoming Connection") { if (hasBluetoothPermission()) peer?.listen() else requestAppPermissions() }
        label("Nearby devices")
        if (found.isEmpty()) label("No devices found yet. Turn on Bluetooth on both phones and scan.")
        found.values.forEach { device ->
            @SuppressLint("MissingPermission")
            val title = try { "${device.name ?: "Unknown device"} (${device.address})" } catch (_: Exception) { "Bluetooth device" }
            button("Connect: $title") { if (hasBluetoothPermission()) peer?.connect(device) else requestAppPermissions() }
        }
        label("Messages")
        button("Disconnect") { peer?.disconnect() }
        val message = edit("Type a message")
        content.addView(message)
        button("Send Message") {
            val value = message.text.toString().trim()
            if (value.isNotEmpty()) {
                if (peer?.isConnected() == true) {
                    peer?.sendMessage("$username: $value")
                    addBubble("Me: $value")
                    message.setText("")
                } else toast("Connect to a nearby device first")
            }
        }
        button("Record Voice Note") { startVoiceNote() }
        button("Stop and Send Voice Note") { stopVoiceNote(true) }
        button("Send SMS") { showSmsDialog() }
    }

    private fun showCalls() {
        content.removeAllViews(); heading("Calls")
        label("Bluetooth audio calls work between Nearby Connect users while connected.")
        button("Start Audio Call") {
            if (peer?.isConnected() != true) toast("Connect to a nearby device first")
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
            else startAudioCall()
        }
        button("End Call") { stopAudioCall(); peer?.endCall() }
        button("Back to Chats") { tab = "Chats"; showChats() }
    }

    private fun showSettings() {
        content.removeAllViews(); heading("Settings")
        val name = edit("Username"); name.setText(username); content.addView(name)
        button("Save Username") { val value = name.text.toString().trim(); if (value.length >= 3) { username = value; getPreferences(MODE_PRIVATE).edit().putString("username", value).apply(); toast("Username saved") } else toast("Enter at least 3 characters") }
        button("Grant / Review Permissions") { requestAppPermissions() }
        button("Open Bluetooth Settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("About Nearby Connect") { label("Version 1.0. Bluetooth Classic P2P messenger. No internet server is used.") }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!hasBluetoothPermission()) { requestAppPermissions(); return }
        if (!adapter.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        found.clear()
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery(); setStatus("Searching for nearby Bluetooth devices…")
        } catch (e: Exception) { setStatus("Scan error: ${e.message}") }
    }

    private fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestAppPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.SEND_SMS)
        if (needed.isEmpty()) toast("All requested permissions are already granted") else requestPermissions(needed.toTypedArray(), 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startAudioCall()
        else if (requestCode == 300) toast("Permission selection saved. You can grant skipped permissions later in Settings.")
    }

    private fun startVoiceNote() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 302); return }
        try {
            noteFile = File(cacheDir, "voice-note-${System.currentTimeMillis()}.m4a")
            recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder?.apply { setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(64000); setAudioSamplingRate(22050); setOutputFile(noteFile!!.absolutePath); prepare(); start() }
            toast("Recording voice note. Press Stop and Send Voice Note when finished.")
        } catch (e: Exception) { toast("Recording failed: ${e.message}"); stopVoiceNote(false) }
    }

    private fun stopVoiceNote(send: Boolean) {
        try { recorder?.stop() } catch (_: Exception) {}; try { recorder?.release() } catch (_: Exception) {}; recorder = null
        val file = noteFile; noteFile = null
        if (send && file != null && file.exists() && file.length() > 0) {
            if (peer?.isConnected() == true) {
                peer?.sendVoiceNote(file.readBytes())
                addBubble("Voice note sent")
                toast("Voice note sent")
            } else {
                toast("Not connected. Voice note was not sent.")
            }
        }
        file?.delete()
    }

    private fun startAudioCall() {
        if (peer?.isConnected() != true) { toast("Connect to a nearby device first"); return }
        try {
            peer?.startCall()
            startAudioCapture()
            toast("Audio call started")
        } catch (e: Exception) { stopAudioCall(); toast("Call could not start: ${e.message}") }
    }

    private fun startAudioCapture() {
        if (callRecording.get()) return
        try {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            audioRecord?.startRecording(); callRecording.set(true)
            Thread { val buffer = ByteArray(min); while (callRecording.get()) { val count = try { audioRecord?.read(buffer, 0, buffer.size) ?: -1 } catch (_: Exception) { -1 }; if (count > 0) peer?.sendCallAudio(buffer.copyOf(count)) } }.start()
        } catch (e: Exception) { stopAudioCall(); setStatus("Microphone could not start: ${e.message}") }
    }

    private fun stopAudioCall() {
        callRecording.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}; try { audioRecord?.release() } catch (_: Exception) {}; audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}; try { audioTrack?.release() } catch (_: Exception) {}; audioTrack = null
    }

    private fun playCallAudio(bytes: ByteArray) {
        try {
            if (audioTrack == null) {
                val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
                audioTrack = AudioTrack.Builder().setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(min * 2).build()
                audioTrack?.play()
            }
            audioTrack?.write(bytes, 0, bytes.size)
        } catch (_: Exception) { }
    }

    private fun showSmsDialog() {
        val phone = edit("Phone number, e.g. +923001234567")
        val body = edit("SMS message")
        content.addView(phone); content.addView(body)
        button("Send SMS Now") {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 303); return@button }
            try { SmsManager.getDefault().sendTextMessage(phone.text.toString().trim(), null, body.text.toString(), null, null); toast("SMS submitted to mobile network") }
            catch (e: Exception) { toast("SMS failed: ${e.message}") }
        }
        label("SMS uses your SIM/mobile network and may incur charges. It is not sent over Bluetooth.")
    }

    private fun addBubble(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 16f; setTextColor(0xFF173C36.toInt()); setPadding(12, 12, 12, 12); setBackgroundColor(-1); contentDescription = text }) }
    private fun setStatus(message: String) { runOnUiThread { status.text = message; status.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT) } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStatus(message: String) = setStatus(message)
    override fun onMessage(message: String, mine: Boolean) { runOnUiThread { if (tab != "Chats") { tab = "Chats"; showChats() }; addBubble(message) } }
    override fun onVoiceNote(bytes: ByteArray) { runOnUiThread { val file = File(getExternalFilesDir(null), "received-${System.currentTimeMillis()}.m4a"); file.writeBytes(bytes); addBubble("Voice note received: ${file.name}"); toast("Voice note received and saved") } }
    override fun onCallAudio(bytes: ByteArray) { playCallAudio(bytes) }
    override fun onCallState(active: Boolean) {
        if (active) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAudioCapture()
            else runOnUiThread { toast("Allow microphone permission to answer the call") }
        } else stopAudioCall()
    }

    override fun onDestroy() {
        stopVoiceNote(false); stopAudioCall(); peer?.disconnect()
        try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
