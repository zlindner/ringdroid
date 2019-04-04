/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ringdroid.soundfile;

import android.util.Log;

class Atom {  // note: latest versions of spec simply call it 'box' instead of 'atom'.
    private int size;  // includes atom header (8 bytes)
    private int type;
    private byte[] data;  // an atom can either contain data or children, but not both.
    private Atom[] children;
    private byte version;  // if negative, then the atom does not contain version and flags data.
    private int flags;

    // create an empty atom of the given type.
    public Atom(String type) {
        size = 8;
        this.type = getTypeInt(type);
        data = null;
        children = null;
        version = -1;
        flags = 0;
    }

    // create an empty atom of type type, with a given version and flags.
    public Atom(String type, byte version, int flags) {
        size = 12;
        this.type = getTypeInt(type);
        data = null;
        children = null;
        this.version = version;
        this.flags = flags;
    }

    // set the size field of the atom based on its content.
    private void setSize() {
        int size = 8;  // type + size
        if (version >= 0) {
            size += 4; // version + flags
        }
        if (data != null) {
            size += data.length;
        } else if (children != null) {
            for (Atom child : children) {
                size += child.getSize();
            }
        }
        this.size = size;
    }

    // get the size of the this atom.
    public int getSize() {
        return size;
    }

    private int getTypeInt(String type_str) {
        int type = 0;
        type |= (byte)(type_str.charAt(0)) << 24;
        type |= (byte)(type_str.charAt(1)) << 16;
        type |= (byte)(type_str.charAt(2)) << 8;
        type |= (byte)(type_str.charAt(3));
        return type;
    }

    public int getTypeInt() {
        return type;
    }

    public String getTypeStr() {
        String type = "";
        type += (char)((byte)((this.type >> 24) & 0xFF));
        type += (char)((byte)((this.type >> 16) & 0xFF));
        type += (char)((byte)((this.type >> 8) & 0xFF));
        type += (char)((byte)(this.type & 0xFF));
        return type;
    }

    public boolean setData(byte[] data) {
        if (children != null || data == null) {
            Log.i("Atom", "setData() children are non-null or data is null");
            return false;
        }

        this.data = data;
        setSize();

        return true;
    }

    public byte[] getData() {
        return data;
    }

    public boolean addChild(Atom child) {
        if (data != null || child == null) {
            Log.i("Atom", "addChild() data is non-null or child is null");
            return false;
        }

        int numChildren = 1;

        if (children != null) {
            numChildren += children.length;
        }

        Atom[] children = new Atom[numChildren];

        if (this.children != null) {
            System.arraycopy(this.children, 0, children, 0, this.children.length);
        }

        children[numChildren - 1] = child;
        this.children = children;
        setSize();

        return true;
    }

    // return the child atom of the corresponding type.
    // type can contain grand children: e.g. type = "trak.mdia.minf"
    // return null if the atom does not contain such a child.
    public Atom getChild(String type) {
        if (children == null) {
            return null;
        }
        String[] types = type.split("\\.", 2);
        for (Atom child : children) {
            if (child.getTypeStr().equals(types[0])) {
                if (types.length == 1) {
                    return child;
                } else {
                    return child.getChild(types[1]);
                }
            }
        }
        return null;
    }

    // return a byte array containing the full content of the atom (including header)
    public byte[] getBytes() {
        byte[] atom_bytes = new byte[size];
        int offset = 0;

        atom_bytes[offset++] = (byte)((size >> 24) & 0xFF);
        atom_bytes[offset++] = (byte)((size >> 16) & 0xFF);
        atom_bytes[offset++] = (byte)((size >> 8) & 0xFF);
        atom_bytes[offset++] = (byte)(size & 0xFF);
        atom_bytes[offset++] = (byte)((type >> 24) & 0xFF);
        atom_bytes[offset++] = (byte)((type >> 16) & 0xFF);
        atom_bytes[offset++] = (byte)((type >> 8) & 0xFF);
        atom_bytes[offset++] = (byte)(type & 0xFF);
        if (version >= 0) {
            atom_bytes[offset++] = version;
            atom_bytes[offset++] = (byte)((flags >> 16) & 0xFF);
            atom_bytes[offset++] = (byte)((flags >> 8) & 0xFF);
            atom_bytes[offset++] = (byte)(flags & 0xFF);
        }
        if (data != null) {
            System.arraycopy(data, 0, atom_bytes, offset, data.length);
        } else if (children != null) {
            byte[] child_bytes;
            for (Atom child : children) {
                child_bytes = child.getBytes();
                System.arraycopy(child_bytes, 0, atom_bytes, offset, child_bytes.length);
                offset += child_bytes.length;
            }
        }
        return atom_bytes;
    }

    // Used for debugging purpose only.
    @Override
    public String toString() {
        String str = "";
        byte[] atom_bytes = getBytes();

        for (int i = 0; i < atom_bytes.length; i++) {
            if(i % 8 == 0 && i > 0) {
                str += '\n';
            }
            str += String.format("0x%02X", atom_bytes[i]);
            if (i < atom_bytes.length - 1) {
                str += ',';
                if (i % 8 < 7) {
                    str += ' ';
                }
            }
        }
        str += '\n';
        return str;
    }
}

public class MP4Header {
    private int[] frameSize;    // size of each AAC frames, in bytes. First one should be 2.
    private int maxFrameSize;   // size of the biggest frame.
    private int totalSize;        // size of the AAC stream.
    private int bitrate;        // bitrate used to encode the AAC stream.
    private byte[] time;        // time used for 'creation time' and 'modification time' fields.
    private byte[] duration;  // duration of stream in milliseconds.
    private byte[] numSamples;  // number of samples in the stream.
    private byte[] header;      // the complete header.
    private int sampleRate;     // sampling frequency in Hz (e.g. 44100).
    private int channels;       // number of channels.

    // Creates a new MP4Header object that should be used to generate an .m4a file header.
    public MP4Header(int sampleRate, int numChannels, int[] frameSize, int bitrate) {
        if (frameSize == null || frameSize.length < 2 || frameSize[0] != 2) {
            Log.e("MP4Header", "constructor invalid frameSize");
            return;
        }

        this.sampleRate = sampleRate;
        channels = numChannels;
        this.frameSize = frameSize;
        this.bitrate = bitrate;
        maxFrameSize = this.frameSize[0];
        totalSize = this.frameSize[0];
        for (int i = 1; i< this.frameSize.length; i++) {
            if (maxFrameSize < this.frameSize[i]) {
                maxFrameSize = this.frameSize[i];
            }
            totalSize += this.frameSize[i];
        }
        long time = System.currentTimeMillis() / 1000;
        time += (66 * 365 + 16) * 24 * 60 * 60;  // number of seconds between 1904 and 1970
        this.time = new byte[4];
        this.time[0] = (byte)((time >> 24) & 0xFF);
        this.time[1] = (byte)((time >> 16) & 0xFF);
        this.time[2] = (byte)((time >> 8) & 0xFF);
        this.time[3] = (byte)(time & 0xFF);
        int numSamples = 1024 * (frameSize.length - 1);  // 1st frame does not contain samples.
        int durationMS = (numSamples * 1000) / this.sampleRate;
        if ((numSamples * 1000) % this.sampleRate > 0) {  // round the duration up.
            durationMS++;
        }
        this.numSamples = new byte[] {
                (byte)((numSamples >> 26) & 0XFF),
                (byte)((numSamples >> 16) & 0XFF),
                (byte)((numSamples >> 8) & 0XFF),
                (byte)(numSamples & 0XFF)
        };
        this.duration = new byte[] {
                (byte)((durationMS >> 26) & 0XFF),
                (byte)((durationMS >> 16) & 0XFF),
                (byte)((durationMS >> 8) & 0XFF),
                (byte)(durationMS & 0XFF)
        };
        setHeader();
    }

    public byte[] getMP4Header() {
        return header;
    }

    public static byte[] getMP4Header(
            int sampleRate, int numChannels, int[] frame_size, int bitrate) {
        return new MP4Header(sampleRate, numChannels, frame_size, bitrate).header;
    }

    public String toString() {
        String str = "";
        if (header == null) {
            return str;
        }
        int num_32bits_per_lines = 8;
        int count = 0;
        for (byte b : header) {
            boolean break_line = count > 0 && count % (num_32bits_per_lines * 4) == 0;
            boolean insert_space = count > 0 && count % 4 == 0 && !break_line;
            if (break_line) {
                str += '\n';
            }
            if (insert_space) {
                str += ' ';
            }
            str += String.format("%02X", b);
            count++;
        }

        return str;
    }

    private void setHeader() {
        // create the atoms needed to build the header.
        Atom a_ftyp = getFTYPAtom();
        Atom a_moov = getMOOVAtom();
        Atom a_mdat = new Atom("mdat");  // create an empty atom. The AAC stream data should follow
                                         // immediately after. The correct size will be set later.

        // set the correct chunk offset in the stco atom.
        Atom a_stco = a_moov.getChild("trak.mdia.minf.stbl.stco");
        if (a_stco == null) {
            header = null;
            return;
        }
        byte[] data = a_stco.getData();
        int chunk_offset = a_ftyp.getSize() + a_moov.getSize() + a_mdat.getSize();
        int offset = data.length - 4;  // here stco should contain only one chunk offset.
        data[offset++] = (byte)((chunk_offset >> 24) & 0xFF);
        data[offset++] = (byte)((chunk_offset >> 16) & 0xFF);
        data[offset++] = (byte)((chunk_offset >> 8) & 0xFF);
        data[offset++] = (byte)(chunk_offset & 0xFF);

        // create the header byte array based on the previous atoms.
        byte[] header = new byte[chunk_offset];  // here chunk_offset is also the size of the header
        offset = 0;
        for (Atom atom : new Atom[] {a_ftyp, a_moov, a_mdat}) {
            byte[] atom_bytes = atom.getBytes();
            System.arraycopy(atom_bytes, 0, header, offset, atom_bytes.length);
            offset += atom_bytes.length;
        }

        //set the correct size of the mdat atom
        int size = 8 + totalSize;
        offset -= 8;
        header[offset++] = (byte)((size >> 24) & 0xFF);
        header[offset++] = (byte)((size >> 16) & 0xFF);
        header[offset++] = (byte)((size >> 8) & 0xFF);
        header[offset++] = (byte)(size & 0xFF);

        this.header = header;
    }

    private Atom getFTYPAtom() {
        Atom atom = new Atom("ftyp");
        atom.setData(new byte[] {
                'M', '4', 'A', ' ',  // Major brand
                0, 0, 0, 0,          // Minor version
                'M', '4', 'A', ' ',  // compatible brands
                'm', 'p', '4', '2',
                'i', 's', 'o', 'm'
        });
        return atom;
    }

    private Atom getMOOVAtom() {
        Atom atom = new Atom("moov");
        atom.addChild(getMVHDAtom());
        atom.addChild(getTRAKAtom());
        return atom;
    }

    private Atom getMVHDAtom() {
        Atom atom = new Atom("mvhd", (byte)0, 0);
        atom.setData(new byte[] {
                time[0], time[1], time[2], time[3],  // creation time.
                time[0], time[1], time[2], time[3],  // modification time.
                0, 0, 0x03, (byte)0xE8,  // timescale = 1000 => duration expressed in ms.
                duration[0], duration[1], duration[2], duration[3],  // duration in ms.
                0, 1, 0, 0,  // rate = 1.0
                1, 0,        // volume = 1.0
                0, 0,        // reserved
                0, 0, 0, 0,  // reserved
                0, 0, 0, 0,  // reserved
                0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,  // unity matrix
                0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0,
                0, 0, 0, 0,  // pre-defined
                0, 0, 0, 0,  // pre-defined
                0, 0, 0, 0,  // pre-defined
                0, 0, 0, 0,  // pre-defined
                0, 0, 0, 0,  // pre-defined
                0, 0, 0, 0,  // pre-defined
                0, 0, 0, 2   // next track ID
        });
        return atom;
    }

    private Atom getTRAKAtom() {
        Atom atom = new Atom("trak");
        atom.addChild(getTKHDAtom());
        atom.addChild(getMDIAAtom());
        return atom;
    }

    private Atom getTKHDAtom() {
        Atom atom = new Atom("tkhd", (byte)0, 0x07);  // track enabled, in movie, and in preview.
        atom.setData(new byte[] {
                time[0], time[1], time[2], time[3],  // creation time.
                time[0], time[1], time[2], time[3],  // modification time.
                0, 0, 0, 1,  // track ID
                0, 0, 0, 0,  // reserved
                duration[0], duration[1], duration[2], duration[3],  // duration in ms.
                0, 0, 0, 0,  // reserved
                0, 0, 0, 0,  // reserved
                0, 0,        // layer
                0, 0,        // alternate group
                1, 0,        // volume = 1.0
                0, 0,        // reserved
                0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,  // unity matrix
                0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0,
                0, 0, 0, 0,  // width
                0, 0, 0, 0   // height
        });
        return atom;
    }

    private Atom getMDIAAtom() {
        Atom atom = new Atom("mdia");
        atom.addChild(getMDHDAtom());
        atom.addChild(getHDLRAtom());
        atom.addChild(getMINFAtom());
        return atom;
    }

    private Atom getMDHDAtom() {
        Atom atom = new Atom("mdhd", (byte)0, 0);
        atom.setData(new byte[] {
                time[0], time[1], time[2], time[3],  // creation time.
                time[0], time[1], time[2], time[3],  // modification time.
                (byte)(sampleRate >> 24), (byte)(sampleRate >> 16),  // timescale = Fs =>
                (byte)(sampleRate >> 8), (byte)(sampleRate),  // duration expressed in samples.
                numSamples[0], numSamples[1], numSamples[2], numSamples[3],  // duration
                0, 0,     // languages
                0, 0      // pre-defined
        });
        return atom;
    }

    private Atom getHDLRAtom() {
        Atom atom = new Atom("hdlr", (byte)0, 0);
        atom.setData(new byte[] {
                0, 0, 0, 0,  // pre-defined
                's', 'o', 'u', 'n',  // handler type
                0, 0, 0, 0,  // reserved
                0, 0, 0, 0,  // reserved
                0, 0, 0, 0,  // reserved
                'S', 'o', 'u', 'n',  // name (used only for debugging and inspection purposes).
                'd', 'H', 'a', 'n',
                'd', 'l', 'e', '\0'
        });
        return atom;
    }

    private Atom getMINFAtom() {
        Atom atom = new Atom("minf");
        atom.addChild(getSMHDAtom());
        atom.addChild(getDINFAtom());
        atom.addChild(getSTBLAtom());
        return atom;
    }

    private Atom getSMHDAtom() {
        Atom atom = new Atom("smhd", (byte)0, 0);
        atom.setData(new byte[] {
                0, 0,     // balance (center)
                0, 0      // reserved
        });
        return atom;
    }

    private Atom getDINFAtom() {
        Atom atom = new Atom("dinf");
        atom.addChild(getDREFAtom());
        return atom;
    }

    private Atom getDREFAtom() {
        Atom atom = new Atom("dref", (byte)0, 0);
        byte[] url = getURLAtom().getBytes();
        byte[] data = new byte[4 + url.length];
        data[3] = 0x01;  // entry count = 1
        System.arraycopy(url, 0, data, 4, url.length);
        atom.setData(data);
        return atom;
    }

    private Atom getURLAtom() {
        Atom atom = new Atom("url ", (byte)0, 0x01);  // flags = 0x01: data is self contained.
        return atom;
    }

    private Atom getSTBLAtom() {
        Atom atom = new Atom("stbl");
        atom.addChild(getSTSDAtom());
        atom.addChild(getSTTSAtom());
        atom.addChild(getSTSCAtom());
        atom.addChild(getSTSZAtom());
        atom.addChild(getSTCOAtom());
        return atom;
    }

    private Atom getSTSDAtom() {
        Atom atom = new Atom("stsd", (byte)0, 0);
        byte[] mp4a = getMP4AAtom().getBytes();
        byte[] data = new byte[4 + mp4a.length];
        data[3] = 0x01;  // entry count = 1
        System.arraycopy(mp4a, 0, data, 4, mp4a.length);
        atom.setData(data);
        return atom;
    }

    // See also Part 14 section 5.6.1 of ISO/IEC 14496 for this atom.
    private Atom getMP4AAtom() {
        Atom atom = new Atom("mp4a");
        byte[] ase = new byte[] {  // Audio Sample Entry data
                0, 0, 0, 0, 0, 0,  // reserved
                0, 1,  // data reference index
                0, 0, 0, 0,  // reserved
                0, 0, 0, 0,  // reserved
                (byte)(channels >> 8), (byte) channels,  // channel count
                0, 0x10, // sample size
                0, 0,  // pre-defined
                0, 0,  // reserved
                (byte)(sampleRate >> 8), (byte)(sampleRate), 0, 0,  // sample rate
        };
        byte[] esds = getESDSAtom().getBytes();
        byte[] data = new byte[ase.length + esds.length];
        System.arraycopy(ase, 0, data, 0, ase.length);
        System.arraycopy(esds, 0, data, ase.length, esds.length);
        atom.setData(data);
        return atom;
    }

    private Atom getESDSAtom() {
        Atom atom = new Atom("esds", (byte)0, 0);
        atom.setData(getESDescriptor());
        return atom;
    }

    // Returns an ES Descriptor for an ISO/IEC 14496-3 audio stream, AAC LC, 44100Hz, 2 channels,
    // 1024 samples per frame per channel. The decoder buffer size is set so that it can contain at
    // least 2 frames. (See section 7.2.6.5 of ISO/IEC 14496-1 for more details).
    private byte[] getESDescriptor() {
        int[] samplingFrequencies = new int[] {96000, 88200, 64000, 48000, 44100, 32000, 24000,
                22050, 16000, 12000, 11025, 8000, 7350};
        // First 5 bytes of the ES Descriptor.
        byte[] ESDescriptor_top = new byte[] {0x03, 0x19, 0x00, 0x00, 0x00};
        // First 4 bytes of Decoder Configuration Descriptor. Audio ISO/IEC 14496-3, AudioStream.
        byte[] decConfigDescr_top = new byte[] {0x04, 0x11, 0x40, 0x15};
        // Audio Specific Configuration: AAC LC, 1024 samples/frame/channel.
        // Sampling frequency and channels configuration are not set yet.
        byte[] audioSpecificConfig = new byte[] {0x05, 0x02, 0x10, 0x00};
        byte[] slConfigDescr = new byte[] {0x06, 0x01, 0x02};  // specific for MP4 file.
        int offset;
        int bufferSize = 0x300;
        while (bufferSize < 2 * maxFrameSize) {
            // TODO(nfaralli): what should be the minimum size of the decoder buffer?
            // Should it be a multiple of 256?
            bufferSize += 0x100;
        }

        // create the Decoder Configuration Descriptor
        byte[] decConfigDescr = new byte[2 + decConfigDescr_top[1]];
        System.arraycopy(decConfigDescr_top, 0, decConfigDescr, 0, decConfigDescr_top.length);
        offset = decConfigDescr_top.length;
        decConfigDescr[offset++] = (byte)((bufferSize >> 16) & 0xFF);
        decConfigDescr[offset++] = (byte)((bufferSize >> 8) & 0xFF);
        decConfigDescr[offset++] = (byte)(bufferSize & 0xFF);
        decConfigDescr[offset++] = (byte)((bitrate >> 24) & 0xFF);
        decConfigDescr[offset++] = (byte)((bitrate >> 16) & 0xFF);
        decConfigDescr[offset++] = (byte)((bitrate >> 8) & 0xFF);
        decConfigDescr[offset++] = (byte)(bitrate & 0xFF);
        decConfigDescr[offset++] = (byte)((bitrate >> 24) & 0xFF);
        decConfigDescr[offset++] = (byte)((bitrate >> 16) & 0xFF);
        decConfigDescr[offset++] = (byte)((bitrate >> 8) & 0xFF);
        decConfigDescr[offset++] = (byte)(bitrate & 0xFF);
        int index;
        for (index=0; index<samplingFrequencies.length; index++) {
            if (samplingFrequencies[index] == sampleRate) {
                break;
            }
        }
        if (index == samplingFrequencies.length) {
            Log.i("MP4Header", "getESDescriptor() invalid sampling frequency. Defaulting to 44100Hz");
            index = 4;
        }
        audioSpecificConfig[2] |= (byte)((index >> 1) & 0x07);
        audioSpecificConfig[3] |= (byte)(((index & 1) << 7) | ((channels & 0x0F) << 3));
        System.arraycopy(
                audioSpecificConfig, 0, decConfigDescr, offset, audioSpecificConfig.length);

        // create the ES Descriptor
        byte[] ESDescriptor = new byte[2 + ESDescriptor_top[1]];
        System.arraycopy(ESDescriptor_top, 0, ESDescriptor, 0, ESDescriptor_top.length);
        offset = ESDescriptor_top.length;
        System.arraycopy(decConfigDescr, 0, ESDescriptor, offset, decConfigDescr.length);
        offset += decConfigDescr.length;
        System.arraycopy(slConfigDescr, 0, ESDescriptor, offset, slConfigDescr.length);
        return ESDescriptor;
    }

    private Atom getSTTSAtom() {
        Atom atom = new Atom("stts", (byte)0, 0);
        int numAudioFrames = frameSize.length - 1;
        atom.setData(new byte[] {
                0, 0, 0, 0x02,  // entry count
                0, 0, 0, 0x01,  // first frame contains no audio
                0, 0, 0, 0,
                (byte)((numAudioFrames >> 24) & 0xFF), (byte)((numAudioFrames >> 16) & 0xFF),
                (byte)((numAudioFrames >> 8) & 0xFF), (byte)(numAudioFrames & 0xFF),
                0, 0, 0x04, 0,  // delay between frames = 1024 samples (cf. timescale = Fs)
        });
        return atom;
    }

    private Atom getSTSCAtom() {
        Atom atom = new Atom("stsc", (byte)0, 0);
        int numFrames = frameSize.length;
        atom.setData(new byte[] {
                0, 0, 0, 0x01,  // entry count
                0, 0, 0, 0x01,  // first chunk
                (byte)((numFrames >> 24) & 0xFF), (byte)((numFrames >> 16) & 0xFF),  // samples per
                (byte)((numFrames >> 8) & 0xFF), (byte)(numFrames & 0xFF),           // chunk
                0, 0, 0, 0x01,  // sample description index
        });
        return atom;
    }

    private Atom getSTSZAtom() {
        Atom atom = new Atom("stsz", (byte)0, 0);
        int numFrames = frameSize.length;
        byte[] data = new byte[8 + 4 * numFrames];
        int offset = 0;
        data[offset++] = 0;  // sample size (=0 => each frame can have a different size)
        data[offset++] = 0;
        data[offset++] = 0;
        data[offset++] = 0;
        data[offset++] = (byte)((numFrames >> 24) & 0xFF);  // sample count
        data[offset++] = (byte)((numFrames >> 16) & 0xFF);
        data[offset++] = (byte)((numFrames >> 8) & 0xFF);
        data[offset++] = (byte)(numFrames & 0xFF);
        for (int size : frameSize) {
            data[offset++] = (byte)((size >> 24) & 0xFF);
            data[offset++] = (byte)((size >> 16) & 0xFF);
            data[offset++] = (byte)((size >> 8) & 0xFF);
            data[offset++] = (byte)(size & 0xFF);
        }
        atom.setData(data);
        return atom;
    }

    private Atom getSTCOAtom() {
        Atom atom = new Atom("stco", (byte)0, 0);
        atom.setData(new byte[] {
                0, 0, 0, 0x01,   // entry count
                0, 0, 0, 0  // chunk offset. Set to 0 here. Must be set later. Here it should be
                            // the size of the complete header, as the AAC stream will follow
                            // immediately.
        });
        return atom;
    }
}
