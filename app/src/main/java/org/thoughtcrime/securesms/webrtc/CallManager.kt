package org.thoughtcrime.securesms.webrtc

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.utilities.Debouncer
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.ICE_CANDIDATES
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.webrtc.CallManager.StateEvent.*
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCompat
import org.thoughtcrime.securesms.webrtc.audio.OutgoingRinger
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager.AudioDevice
import org.thoughtcrime.securesms.webrtc.locks.LockManager
import org.thoughtcrime.securesms.webrtc.video.CameraEventListener
import org.thoughtcrime.securesms.webrtc.video.CameraState
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors

class CallManager(context: Context, audioManager: AudioManagerCompat): PeerConnection.Observer,
        SignalAudioManager.EventListener,
        CallDataListener, CameraEventListener, DataChannel.Observer {

    enum class CallState {
        STATE_IDLE, STATE_PRE_OFFER, STATE_DIALING, STATE_ANSWERING, STATE_REMOTE_RINGING, STATE_LOCAL_RINGING, STATE_CONNECTED
    }

    sealed class StateEvent {
        data class AudioEnabled(val isEnabled: Boolean): StateEvent()
        data class VideoEnabled(val isEnabled: Boolean): StateEvent()
        data class CallStateUpdate(val state: CallState): StateEvent()
        data class AudioDeviceUpdate(val selectedDevice: AudioDevice, val audioDevices: Set<AudioDevice>): StateEvent()
        data class RecipientUpdate(val recipient: Recipient?): StateEvent() {
            companion object {
                val UNKNOWN = RecipientUpdate(recipient = null)
            }
        }
    }

    companion object {

        val VIDEO_DISABLED_JSON by lazy { buildJsonObject { put("video", false) } }
        val VIDEO_ENABLED_JSON by lazy { buildJsonObject { put("video", true) } }

        private val TAG = Log.tag(CallManager::class.java)
        val CONNECTED_STATES = arrayOf(CallState.STATE_CONNECTED)
        val PENDING_CONNECTION_STATES = arrayOf(
                CallState.STATE_DIALING,
                CallState.STATE_ANSWERING,
                CallState.STATE_LOCAL_RINGING,
                CallState.STATE_REMOTE_RINGING
        )
        val OUTGOING_STATES = arrayOf(
                CallState.STATE_DIALING,
                CallState.STATE_REMOTE_RINGING,
                CallState.STATE_CONNECTED
        )
        private const val DATA_CHANNEL_NAME = "signaling"
    }

    private val signalAudioManager: SignalAudioManager = SignalAudioManager(context, this, audioManager)

    private val peerConnectionObservers = mutableSetOf<PeerConnection.Observer>()

    fun registerListener(listener: PeerConnection.Observer) {
        peerConnectionObservers.add(listener)
    }

    fun unregisterListener(listener: PeerConnection.Observer) {
        peerConnectionObservers.remove(listener)
    }

    private val _audioEvents = MutableStateFlow(AudioEnabled(false))
    val audioEvents = _audioEvents.asSharedFlow()
    private val _videoEvents = MutableStateFlow(VideoEnabled(false))
    val videoEvents = _videoEvents.asSharedFlow()
    private val _remoteVideoEvents = MutableStateFlow(VideoEnabled(false))
    val remoteVideoEvents = _remoteVideoEvents.asSharedFlow()
    private val _connectionEvents = MutableStateFlow<StateEvent>(CallStateUpdate(CallState.STATE_IDLE))
    val connectionEvents = _connectionEvents.asSharedFlow()
    private val _callStateEvents = MutableStateFlow(CallViewModel.State.CALL_PENDING)
    val callStateEvents = _callStateEvents.asSharedFlow()
    private val _recipientEvents = MutableStateFlow(RecipientUpdate.UNKNOWN)
    val recipientEvents = _recipientEvents.asSharedFlow()
    private var localCameraState: CameraState = CameraState.UNKNOWN

    private val _audioDeviceEvents = MutableStateFlow(AudioDeviceUpdate(AudioDevice.NONE, setOf()))
    val audioDeviceEvents = _audioDeviceEvents.asSharedFlow()

    val currentConnectionState
        get() = (_connectionEvents.value as CallStateUpdate).state

    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var eglBase: EglBase? = null

    var pendingOffer: String? = null
    var pendingOfferTime: Long = -1
    var preOfferCallData: PreOffer? = null
    var callId: UUID? = null
    var recipient: Recipient? = null
    set(value) {
        field = value
        _recipientEvents.value = RecipientUpdate(value)
    }
    var isReestablishing: Boolean = false

    private var peerConnection: PeerConnectionWrapper? = null
    private var dataChannel: DataChannel? = null

    private val pendingOutgoingIceUpdates = ArrayDeque<IceCandidate>()
    private val pendingIncomingIceUpdates = ArrayDeque<IceCandidate>()

    private val outgoingIceDebouncer = Debouncer(200L)

    var localRenderer: SurfaceViewRenderer? = null
    var remoteRenderer: SurfaceViewRenderer? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

    fun clearPendingIceUpdates() {
        pendingOutgoingIceUpdates.clear()
        pendingIncomingIceUpdates.clear()
    }

    fun initializeAudioForCall() {
        signalAudioManager.handleCommand(AudioManagerCommand.Initialize)
    }

    fun startOutgoingRinger(ringerType: OutgoingRinger.Type) {
        if (ringerType == OutgoingRinger.Type.RINGING) {
            signalAudioManager.handleCommand(AudioManagerCommand.UpdateAudioDeviceState)
        }
        signalAudioManager.handleCommand(AudioManagerCommand.StartOutgoingRinger(ringerType))
    }

    fun silenceIncomingRinger() {
        signalAudioManager.handleCommand(AudioManagerCommand.SilenceIncomingRinger)
    }

    fun postConnectionEvent(newState: CallState) {
        _connectionEvents.value = CallStateUpdate(newState)
    }

    fun postViewModelState(newState: CallViewModel.State) {
        _callStateEvents.value = newState
    }

    override fun newCallMessage(callMessage: SignalServiceProtos.CallMessage) {

    }

    fun isBusy(context: Context, callId: UUID) = callId != this.callId && (currentConnectionState != CallState.STATE_IDLE
            || context.getSystemService(TelephonyManager::class.java).callState  != TelephonyManager.CALL_STATE_IDLE)

    fun isPreOffer() = currentConnectionState == CallState.STATE_PRE_OFFER

    fun isIdle() = currentConnectionState == CallState.STATE_IDLE

    fun initializeVideo(context: Context) {
        Util.runOnMainSync {
            val base = EglBase.create()
            eglBase = base
            localRenderer = SurfaceViewRenderer(context)
            remoteRenderer = SurfaceViewRenderer(context)

            localRenderer?.init(base.eglBaseContext, null)
            remoteRenderer?.init(base.eglBaseContext, null)

            val encoderFactory = DefaultVideoEncoderFactory(base.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(base.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(object: PeerConnectionFactory.Options() {
                        init {
//                            networkIgnoreMask = 1 shl 4
                        }
                    })
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
        }
    }

    fun callEnded() {
        peerConnection?.dispose()
        peerConnection = null
    }

    fun setAudioEnabled(isEnabled: Boolean) {
        currentConnectionState.withState(*(CONNECTED_STATES + PENDING_CONNECTION_STATES)) {
            peerConnection?.setAudioEnabled(isEnabled)
            _audioEvents.value = AudioEnabled(true)
        }
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState) {
        peerConnectionObservers.forEach { listener -> listener.onSignalingChange(newState) }
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        peerConnectionObservers.forEach { listener -> listener.onIceConnectionChange(newState) }
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        peerConnectionObservers.forEach { listener -> listener.onIceConnectionReceivingChange(receiving) }
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
        peerConnectionObservers.forEach { listener -> listener.onIceGatheringChange(newState) }
    }

    override fun onIceCandidate(iceCandidate: IceCandidate) {
        peerConnectionObservers.forEach { listener -> listener.onIceCandidate(iceCandidate) }
        val expectedCallId = this.callId ?: return
        val expectedRecipient = this.recipient ?: return
        pendingOutgoingIceUpdates.add(iceCandidate)

        if (peerConnection?.readyForIce != true) return

        queueOutgoingIce(expectedCallId, expectedRecipient)
    }

    private fun queueOutgoingIce(expectedCallId: UUID, expectedRecipient: Recipient) {
        outgoingIceDebouncer.publish {
            val currentCallId = this.callId ?: return@publish
            val currentRecipient = this.recipient ?: return@publish
            if (currentCallId == expectedCallId && expectedRecipient == currentRecipient) {
                val currentPendings = mutableSetOf<IceCandidate>()
                while (pendingOutgoingIceUpdates.isNotEmpty()) {
                    currentPendings.add(pendingOutgoingIceUpdates.pop())
                }
                val sdps = currentPendings.map { it.sdp }
                val sdpMLineIndexes = currentPendings.map { it.sdpMLineIndex }
                val sdpMids = currentPendings.map { it.sdpMid }

                MessageSender.sendNonDurably(CallMessage(
                        ICE_CANDIDATES,
                        sdps = sdps,
                        sdpMLineIndexes = sdpMLineIndexes,
                        sdpMids = sdpMids,
                        currentCallId
                ), currentRecipient.address)
            }
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        peerConnectionObservers.forEach { listener -> listener.onIceCandidatesRemoved(candidates) }
    }

    override fun onAddStream(stream: MediaStream) {
        peerConnectionObservers.forEach { listener -> listener.onAddStream(stream) }
        for (track in stream.audioTracks) {
            track.setEnabled(true)
        }

        if (stream.videoTracks != null && stream.videoTracks.size == 1) {
            val videoTrack = stream.videoTracks.first()
            videoTrack.setEnabled(true)
            videoTrack.addSink(remoteRenderer)
        }
    }

    override fun onRemoveStream(p0: MediaStream?) {
        peerConnectionObservers.forEach { listener -> listener.onRemoveStream(p0) }
    }

    override fun onDataChannel(p0: DataChannel?) {
        peerConnectionObservers.forEach { listener -> listener.onDataChannel(p0) }
    }

    override fun onRenegotiationNeeded() {
        peerConnectionObservers.forEach { listener -> listener.onRenegotiationNeeded() }
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        peerConnectionObservers.forEach { listener -> listener.onAddTrack(p0, p1) }
    }

    override fun onBufferedAmountChange(l: Long) {
        Log.i(TAG,"onBufferedAmountChange: $l")
    }

    override fun onStateChange() {
        Log.i(TAG,"onStateChange")
    }

    override fun onMessage(buffer: DataChannel.Buffer?) {
        Log.i(TAG,"onMessage...")
        buffer ?: return

        try {
            val byteArray = ByteArray(buffer.data.remaining()) { buffer.data[it] }
            val videoEnabled = Json.decodeFromString(VideoEnabledMessage.serializer(), byteArray.decodeToString())
            _remoteVideoEvents.value = VideoEnabled(videoEnabled.video)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize data channel message", e)
        }
    }

    override fun onAudioDeviceChanged(activeDevice: AudioDevice, devices: Set<AudioDevice>) {
        _audioDeviceEvents.value = AudioDeviceUpdate(activeDevice, devices)
    }

    private fun CallState.withState(vararg expected: CallState, transition: () -> Unit) {
        if (this in expected) transition()
        else Log.w(TAG,"Tried to transition state $this but expected $expected")
    }

    fun stop() {
        signalAudioManager.handleCommand(AudioManagerCommand.Stop(currentConnectionState in OUTGOING_STATES))
        peerConnection?.dispose()
        peerConnection = null

        localRenderer?.release()
        remoteRenderer?.release()
        eglBase?.release()

        localRenderer = null
        remoteRenderer = null
        eglBase = null

        _connectionEvents.value = CallStateUpdate(CallState.STATE_IDLE)
        localCameraState = CameraState.UNKNOWN
        recipient = null
        callId = null
        _audioEvents.value = AudioEnabled(false)
        _videoEvents.value = VideoEnabled(false)
        _remoteVideoEvents.value = VideoEnabled(false)
        pendingOutgoingIceUpdates.clear()
        pendingIncomingIceUpdates.clear()
    }

    override fun onCameraSwitchCompleted(newCameraState: CameraState) {
        localCameraState = newCameraState
    }

    fun onPreOffer(callId: UUID, recipient: Recipient) {
        if (preOfferCallData != null) {
            Log.d(TAG, "Received new pre-offer when we are already expecting one")
        }
        this.recipient = recipient
        this.callId = callId
        preOfferCallData = PreOffer(callId, recipient)
        postConnectionEvent(CallState.STATE_PRE_OFFER)
    }

    fun onNewOffer(offer: String, callId: UUID, recipient: Recipient): Promise<Unit, Exception> {
        if (callId != this.callId) return Promise.ofFail(NullPointerException("No callId"))
        if (recipient != this.recipient) return Promise.ofFail(NullPointerException("No recipient"))

        val connection = peerConnection ?: return Promise.ofFail(NullPointerException("No peer connection"))

        connection.setNewOffer(SessionDescription(SessionDescription.Type.OFFER, offer))
        val answer = connection.createAnswer(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        })
        return MessageSender.sendNonDurably(CallMessage.answer(answer.description, callId), recipient.address)
    }

    fun onIncomingRing(offer: String, callId: UUID, recipient: Recipient, callTime: Long) {
        if (currentConnectionState !in arrayOf(CallState.STATE_IDLE, CallState.STATE_PRE_OFFER)) return

        this.callId = callId
        this.recipient = recipient
        this.pendingOffer = offer
        this.pendingOfferTime = callTime
        startIncomingRinger()
    }

    fun onIncomingCall(context: Context, isAlwaysTurn: Boolean = false): Promise<Unit, Exception> {
        val callId = callId ?: return Promise.ofFail(NullPointerException("callId is null"))
        val recipient = recipient ?: return Promise.ofFail(NullPointerException("recipient is null"))
        val offer = pendingOffer ?: return Promise.ofFail(NullPointerException("pendingOffer is null"))
        val factory = peerConnectionFactory ?: return Promise.ofFail(NullPointerException("peerConnectionFactory is null"))
        val local = localRenderer ?: return Promise.ofFail(NullPointerException("localRenderer is null"))
        val base = eglBase ?: return Promise.ofFail(NullPointerException("eglBase is null"))
        val connection = PeerConnectionWrapper(
                context,
                factory,
                this,
                local,
                this,
                base,
                isAlwaysTurn
        )
        peerConnection = connection
        localCameraState = connection.getCameraState()
        val dataChannel = connection.createDataChannel(DATA_CHANNEL_NAME)
        this.dataChannel = dataChannel
        dataChannel.registerObserver(this)
        connection.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offer))
        val answer = connection.createAnswer(MediaConstraints())
        connection.setLocalDescription(answer)

        val answerMessage = MessageSender.sendNonDurably(CallMessage.answer(
                answer.description,
                callId
        ), recipient.address)

        while (pendingIncomingIceUpdates.isNotEmpty()) {
            val candidate = pendingIncomingIceUpdates.pop() ?: break
            connection.addIceCandidate(candidate)
        }
        return answerMessage.success {
            pendingOffer = null
            pendingOfferTime = -1
        }
    }

    fun onOutgoingCall(context: Context, isAlwaysTurn: Boolean = false): Promise<Unit, Exception> {
        val callId = callId ?: return Promise.ofFail(NullPointerException("callId is null"))
        val recipient = recipient
                ?: return Promise.ofFail(NullPointerException("recipient is null"))
        val factory = peerConnectionFactory
                ?: return Promise.ofFail(NullPointerException("peerConnectionFactory is null"))
        val local = localRenderer
                ?: return Promise.ofFail(NullPointerException("localRenderer is null"))
        val base = eglBase ?: return Promise.ofFail(NullPointerException("eglBase is null"))

        val connection = PeerConnectionWrapper(
                context,
                factory,
                this,
                local,
                this,
                base,
                isAlwaysTurn
        )

        peerConnection = connection
        localCameraState = connection.getCameraState()
        val dataChannel = connection.createDataChannel(DATA_CHANNEL_NAME)
        dataChannel.registerObserver(this)
        this.dataChannel = dataChannel
        val offer = connection.createOffer(MediaConstraints())
        connection.setLocalDescription(offer)

        Log.i(TAG, "Sending offer: ${offer.description}")

        return MessageSender.sendNonDurably(CallMessage.preOffer(
                callId
        ), recipient.address).bind {
            MessageSender.sendNonDurably(CallMessage.offer(
                    offer.description,
                    callId
            ), recipient.address)
        }
    }

    fun callNotSetup(): Boolean =
            peerConnection == null || dataChannel == null || recipient == null || callId == null

    fun handleDenyCall() {
        val callId = callId ?: return
        val recipient = recipient ?: return
        MessageSender.sendNonDurably(CallMessage.endCall(callId), recipient.address)
    }

    fun handleLocalHangup() {
        val recipient = recipient ?: return
        val callId = callId ?: return

        postViewModelState(CallViewModel.State.CALL_DISCONNECTED)
        MessageSender.sendNonDurably(CallMessage.endCall(callId), recipient.address)
    }

    fun handleRemoteHangup() {
        when (currentConnectionState) {
            CallState.STATE_DIALING,
            CallState.STATE_REMOTE_RINGING -> postViewModelState(CallViewModel.State.RECIPIENT_UNAVAILABLE)
            else -> postViewModelState(CallViewModel.State.CALL_DISCONNECTED)
        }
    }

    fun handleSetMuteAudio(muted: Boolean) {
        _audioEvents.value = AudioEnabled(!muted)
        peerConnection?.setAudioEnabled(!muted)
    }

    fun handleSetMuteVideo(muted: Boolean, lockManager: LockManager) {
        _videoEvents.value = VideoEnabled(!muted)
        peerConnection?.setVideoEnabled(!muted)
        dataChannel?.let { channel ->
            val toSend = if (muted) VIDEO_DISABLED_JSON else VIDEO_ENABLED_JSON
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(toSend.toString().encodeToByteArray()), false)
            channel.send(buffer)
        }

        if (currentConnectionState == CallState.STATE_CONNECTED) {
            if (localCameraState.enabled) lockManager.updatePhoneState(LockManager.PhoneState.IN_VIDEO)
            else lockManager.updatePhoneState(LockManager.PhoneState.IN_CALL)
        }

        if (localCameraState.enabled
                && !signalAudioManager.isSpeakerphoneOn()
                && !signalAudioManager.isBluetoothScoOn()
                && !signalAudioManager.isWiredHeadsetOn()
        ) {
            signalAudioManager.handleCommand(AudioManagerCommand.SetUserDevice(AudioDevice.SPEAKER_PHONE))
        }
    }

    fun handleSetCameraFlip() {
        if (!localCameraState.enabled) return
        peerConnection?.let { connection ->
            connection.flipCamera()
            localCameraState = connection.getCameraState()
        }
    }

    fun postBluetoothAvailable(available: Boolean) {
        // TODO: _bluetoothEnabled.value = available
    }

    fun handleWiredHeadsetChanged(present: Boolean) {
        if (currentConnectionState in arrayOf(CallState.STATE_CONNECTED,
                        CallState.STATE_DIALING,
                        CallState.STATE_REMOTE_RINGING)) {
            if (present && signalAudioManager.isSpeakerphoneOn()) {
                signalAudioManager.handleCommand(AudioManagerCommand.SetUserDevice(AudioDevice.WIRED_HEADSET))
            } else if (!present && !signalAudioManager.isSpeakerphoneOn() && !signalAudioManager.isBluetoothScoOn() && localCameraState.enabled) {
                signalAudioManager.handleCommand(AudioManagerCommand.SetUserDevice(AudioDevice.SPEAKER_PHONE))
            }
        }
    }

    fun handleScreenOffChange() {
        if (currentConnectionState in arrayOf(CallState.STATE_ANSWERING, CallState.STATE_LOCAL_RINGING)) {
            signalAudioManager.handleCommand(AudioManagerCommand.SilenceIncomingRinger)
        }
    }

    fun handleResponseMessage(recipient: Recipient, callId: UUID, answer: SessionDescription) {
        if (currentConnectionState !in arrayOf(CallState.STATE_DIALING, CallState.STATE_CONNECTED) || recipient != this.recipient || callId != this.callId) {
            Log.w(TAG,"Got answer for recipient and call ID we're not currently dialing")
            return
        }

        val connection = peerConnection ?: throw AssertionError("assert")

        connection.setRemoteDescription(answer)
        while (pendingIncomingIceUpdates.isNotEmpty()) {
            connection.addIceCandidate(pendingIncomingIceUpdates.pop())
        }
        queueOutgoingIce(callId, recipient)
    }

    fun handleRemoteIceCandidate(iceCandidates: List<IceCandidate>, callId: UUID) {
        if (callId != this.callId) {
            Log.w(TAG, "Got remote ice candidates for a call that isn't active")
            return
        }

        val connection = peerConnection
        if (connection != null && connection.readyForIce) {
            iceCandidates.forEach { candidate ->
                connection.addIceCandidate(candidate)
            }
        } else {
            pendingIncomingIceUpdates.addAll(iceCandidates)
        }
    }

    fun onDestroy() {
        signalAudioManager.handleCommand(AudioManagerCommand.Shutdown)
    }

    fun startIncomingRinger() {
        signalAudioManager.handleCommand(AudioManagerCommand.StartIncomingRinger(true))
    }

    fun startCommunication(lockManager: LockManager) {
        signalAudioManager.handleCommand(AudioManagerCommand.Start)
        val connection = peerConnection ?: return
        if (localCameraState.enabled) lockManager.updatePhoneState(LockManager.PhoneState.IN_VIDEO)
        else lockManager.updatePhoneState(LockManager.PhoneState.IN_CALL)
        connection.setCommunicationMode()
        setAudioEnabled(true)
        dataChannel?.let { channel ->
            val toSend = if (!_videoEvents.value.isEnabled) VIDEO_DISABLED_JSON else VIDEO_ENABLED_JSON
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(toSend.toString().encodeToByteArray()), false)
            channel.send(buffer)
        }
    }

    fun handleBusyCall(callId: UUID, recipient: Recipient): Promise<Unit, Exception> {
        return MessageSender.sendNonDurably(CallMessage.endCall(callId), recipient.address)
    }

    fun handleAudioCommand(audioCommand: AudioManagerCommand) {
        signalAudioManager.handleCommand(audioCommand)
    }

    fun networkReestablished() {
        val connection = peerConnection ?: return
        val callId = callId ?: return
        val recipient = recipient ?: return

        if (isReestablishing) return
        isReestablishing = true

        val offer = connection.createOffer(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        })
        connection.setLocalDescription(offer)

        MessageSender.sendNonDurably(CallMessage.offer(offer.description, callId), recipient.address).success {
            isReestablishing = false
        }
    }

    @Serializable
    data class VideoEnabledMessage(val video: Boolean)

}