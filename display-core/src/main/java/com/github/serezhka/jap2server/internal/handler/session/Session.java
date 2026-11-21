package com.github.serezhka.jap2server.internal.handler.session;

import com.github.serezhka.jap2lib.AirPlay;

public class Session {

    private final AirPlay airPlay;

    private Thread airPlayReceiverThread;
    private com.github.serezhka.jap2server.internal.MirroringReceiver mirroringReceiver;
    private Thread audioReceiverThread;
    private Thread audioControlServerThread;

    Session() {
        airPlay = new AirPlay();
    }

    public AirPlay getAirPlay() {
        return airPlay;
    }

    public void setMirroringReceiver(com.github.serezhka.jap2server.internal.MirroringReceiver mirroringReceiver) {
        this.mirroringReceiver = mirroringReceiver;
    }

    public void setAirPlayReceiverThread(Thread airPlayReceiverThread) {
        this.airPlayReceiverThread = airPlayReceiverThread;
    }

    public void setAudioReceiverThread(Thread audioReceiverThread) {
        this.audioReceiverThread = audioReceiverThread;
    }

    public void setAudioControlServerThread(Thread audioControlServerThread) {
        this.audioControlServerThread = audioControlServerThread;
    }

    public boolean isMirroringActive() {
        return airPlayReceiverThread != null || mirroringReceiver != null;
    }

    public boolean isAudioActive() {
        return audioReceiverThread != null && audioControlServerThread != null;
    }

    public void stopMirroring() {
        if (mirroringReceiver != null) {
            try {
                mirroringReceiver.stop();
            } catch (Exception ignored) {}
            mirroringReceiver = null;
        }
        if (airPlayReceiverThread != null) {
            airPlayReceiverThread.interrupt();
            airPlayReceiverThread = null;
        }
        if (airPlay != null) {
            airPlay.resetDecryptors();
        }
    }

    public void stopAudio() {
        if (audioReceiverThread != null) {
            audioReceiverThread.interrupt();
            audioReceiverThread = null;
        }
        if (audioControlServerThread != null) {
            audioControlServerThread.interrupt();
            audioControlServerThread = null;
        }
    }
}
