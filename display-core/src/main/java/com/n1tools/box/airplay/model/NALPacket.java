package com.n1tools.box.airplay.model;

public class NALPacket {
    public byte[] nalData = null;
    public int offset = 0;
    public int length = 0;
    public int nalType = 0;
    public long pts = 0;
}
