package com.github.psambit9791.wavfile;

// Wav file IO class
// A.Greensted
// http://www.labbookpages.co.uk

// File format is based on the information from
// http://www.sonicspot.com/guide/wavefiles.html
// http://www.blitter.com/~russtopia/MIDI/~jglatt/tech/wave.htm

// Version 1.0

import java.io.*;

/**
 * Class for reading and writing WAV audio files.
 * <p>
 * Written by <a href="http://www.labbookpages.co.uk/">Andrew Greensted</a>.
 */
public final class WavFile {

    /** Length in bytes of the WAV format header. */
    private static final int WAVFILE_HEADER_LENGTH = 12;

    /** Minimum number of WAV channels. */
    private static final int MIN_CHANNELS = 1;

    /** Maximum number of WAV channels. */
    private static final int MAX_CHANNELS = 65535;

    /** Minimum number of valid bits. */
    private static final int MIN_VALID_BITS = 2;

    /** Maximum number of valid bits. */
    private static final int MAX_VALID_BITS = 65535;

    /** States that our IO operations can be in. */
    private enum IOState {
        /** Reading the buffer. */
        READING,
        /** Writing the buffer. */
        WRITING,
        /** Buffer is closed. */
        CLOSED
    };

    /** Size of our internal buffer. */
    private static final int BUFFER_SIZE = 4096;

    /** WAV-specific constants. */
    private static final int FMT_CHUNK_ID = 0x20746D66;

    /** WAV-specific constants. */
    private static final int DATA_CHUNK_ID = 0x61746164;

    /** WAV-specific constants. */
    private static final int RIFF_CHUNK_ID = 0x46464952;

    /** WAV-specific constants. */
    private static final int RIFF_TYPE_ID = 0x45564157;

    /** Specifies the IO state of the WAV file. Used for sanity checking. */
    private IOState ioState;

    /** Number of bytes required to store a single sample. */
    private int bytesPerSample;

    /** Number of frames within the data section. */
    private long numFrames;

    /** Output stream used for writing data. */
    private FileOutputStream oStream;

    /** Input stream used for reading data. */
    private FileInputStream iStream;

    /** Scaling factor used for int to float conversion. */
    private double floatScale;

    /** Offset factor used for int to float conversion. */
    private double floatOffset;

    /** Specifies if an extra byte at the end of the data chunk is required for word alignment. */
    private boolean wordAlignAdjust;

    /*
     * WAV header information.
     */

    /** 2 bytes unsigned: 0x0001 to 0xFFFF. */
    private int numChannels;

    /** 4 bytes unsigned. 0x00000001 (1) to 0xFFFFFFFF. Need a long since an int is signed. */
    private long sampleRate;

    /** 2 bytes unsigned: 0x0001 to 0xFFFF. */
    private int blockAlign;

    /** 2 bytes unsigned. 0x0002 to 0xFFFF. */
    private int validBits;

    /*
     * Buffering.
     */

    /** Local IO buffer. */
    private byte[] buffer;

    /** Current buffer location. */
    private int bufferPointer;

    /** Number of bytes read. */
    private int bytesRead;

    /** Current number of frames read or written. */
    private long frameCounter;

    /**
     * Cannot instantiate WavFile directly, must either use newWavFile() or openWavFile().
     */
    private WavFile() {
        buffer = new byte[BUFFER_SIZE];
    }

    /**
     * Gets the number of channels.
     *
     * @return the number of channels
     */
    public int getNumChannels() {
        return numChannels;
    }

    /**
     * Get the number of frames.
     *
     * @return the number of frames
     */
    public long getNumFrames() {
        return numFrames;
    }

    /**
     * Get the number of frames remaining.
     *
     * @return the number of frames remaining
     */
    public long getFramesRemaining() {
        return numFrames - frameCounter;
    }

    /**
     * Get the sample rate.
     *
     * @return the sample rate
     */
    public long getSampleRate() {
        return sampleRate;
    }

    /**
     * Get the number of valid bits.
     *
     * @return the number of valid bits
     */
    public int getValidBits() {
        return validBits;
    }

    /**
     * Initialize a new WavFile object for writing into the specified file.
     *
     * @param file the file to write to
     * @param numChannels the number of channels to use
     * @param numFrames the number of frames to use
     * @param validBits the number of alid bits to use
     * @param sampleRate the sample rate for the new file
     * @return the WavFile object
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    @SuppressWarnings("checkstyle:magicnumber")
    public static WavFile newWavFile(final File file, final int numChannels, final long numFrames,
                                     final int validBits, final long sampleRate) throws IOException, WavFileException {

        WavFile wavFile = new WavFile();
        wavFile.numChannels = numChannels;
        wavFile.numFrames = numFrames;
        wavFile.sampleRate = sampleRate;
        wavFile.bytesPerSample = (validBits + 7) / 8;
        wavFile.blockAlign = wavFile.bytesPerSample * numChannels;
        wavFile.validBits = validBits;

        /*
         * Sanity check arguments
         */
        if (numChannels < MIN_CHANNELS || numChannels > MAX_CHANNELS) {
            throw new WavFileException("Illegal number of channels, valid range 1 to 65536");
        }
        if (numFrames < 0) {
            throw new WavFileException("Number of frames must be positive");
        }
        if (validBits < MIN_VALID_BITS || validBits > MAX_VALID_BITS) {
            throw new WavFileException("Illegal number of valid bits, valid range 2 to 65536");
        }
        if (sampleRate < 0) {
            throw new WavFileException("Sample rate must be positive");
        }

        wavFile.oStream = new FileOutputStream(file);

        /*
         * Calculate chunk sizes.
         */
        long dataChunkSize = wavFile.blockAlign * numFrames;
        long mainChunkSize = 4 + // Riff Type
                8 + // Format ID and size
                16 + // Format data
                8 + // Data ID and size
                dataChunkSize;

        /*
         * Chunks must be word aligned, so if odd number of audio data bytes adjust the main chunk
         * size.
         */
        if (dataChunkSize % 2 == 1) {
            mainChunkSize += 1;
            wavFile.wordAlignAdjust = true;
        } else {
            wavFile.wordAlignAdjust = false;
        }

        /*
         * Set the main chunk size.
         */
        putLE(RIFF_CHUNK_ID, wavFile.buffer, 0, 4);
        putLE(mainChunkSize, wavFile.buffer, 4, 4);
        putLE(RIFF_TYPE_ID, wavFile.buffer, 8, 4);

        /*
         * Write the header.
         */
        wavFile.oStream.write(wavFile.buffer, 0, 12);

        /*
         * Put format data into the buffer.
         */
        long averageBytesPerSecond = sampleRate * wavFile.blockAlign;

        putLE(FMT_CHUNK_ID, wavFile.buffer, 0, 4); // Chunk ID
        putLE(16, wavFile.buffer, 4, 4); // Chunk Data Size
        putLE(1, wavFile.buffer, 8, 2); // Compression Code (Uncompressed)
        putLE(numChannels, wavFile.buffer, 10, 2); // Number of channels
        putLE(sampleRate, wavFile.buffer, 12, 4); // Sample Rate
        putLE(averageBytesPerSecond, wavFile.buffer, 16, 4); // Average Bytes Per Second
        putLE(wavFile.blockAlign, wavFile.buffer, 20, 2); // Block Align
        putLE(validBits, wavFile.buffer, 22, 2); // Valid Bits

        /*
         * Write Format Chunk
         */
        wavFile.oStream.write(wavFile.buffer, 0, 24);

        /*
         * Start Data Chunk
         */
        putLE(DATA_CHUNK_ID, wavFile.buffer, 0, 4); // Chunk ID
        putLE(dataChunkSize, wavFile.buffer, 4, 4); // Chunk Data Size

        /*
         * Write Format Chunk
         */
        wavFile.oStream.write(wavFile.buffer, 0, 8);

        /*
         * Calculate the scaling factor for normalized to a double.
         */
        if (wavFile.validBits > 8) {
            // If more than 8 validBits, data is signed
            // Conversion required multiplying by magnitude of max positive value
            wavFile.floatOffset = 0;
            wavFile.floatScale = Long.MAX_VALUE >> (64 - wavFile.validBits);
        } else {
            // Else if 8 or less validBits, data is unsigned
            // Conversion required dividing by max positive value
            wavFile.floatOffset = 1;
            wavFile.floatScale = 0.5 * ((1 << wavFile.validBits) - 1);
        }

        /*
         * Initialize our IO state.
         */
        wavFile.bufferPointer = 0;
        wavFile.bytesRead = 0;
        wavFile.frameCounter = 0;
        wavFile.ioState = IOState.WRITING;

        return wavFile;
    }

    /**
     * Read WAV contents from a provided file.
     *
     * @param file the file
     * @return the wav file
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    @SuppressWarnings({"checkstyle:methodlength",
            "checkstyle:magicnumber",
            "checkstyle:avoidinlineconditionals"})
    public static WavFile openWavFile(final File file) throws IOException, WavFileException {
        /*
         * Instantiate new Wavfile and store the file reference
         */
        WavFile wavFile = new WavFile();

        /*
         * Create a new file input stream for reading file data
         */
        wavFile.iStream = new FileInputStream(file);

        /*
         * Read the first 12 bytes of the file
         */
        int bytesRead = wavFile.iStream.read(wavFile.buffer, 0, WAVFILE_HEADER_LENGTH);
        if (bytesRead != WAVFILE_HEADER_LENGTH) {
            throw new WavFileException("Not enough wav file bytes for header");
        }

        /*
         * Extract parts from the header
         */
        long riffChunkID = getLE(wavFile.buffer, 0, 4);
        long chunkSize = getLE(wavFile.buffer, 4, 4);
        long riffTypeID = getLE(wavFile.buffer, 8, 4);

        /*
         * Check the header bytes for the correct signature
         */
        if (riffChunkID != RIFF_CHUNK_ID) {
            throw new WavFileException("Invalid Wav Header data, incorrect riff chunk ID");
        }
        if (riffTypeID != RIFF_TYPE_ID) {
            throw new WavFileException("Invalid Wav Header data, incorrect riff type ID");
        }

        /*
         * Check that the file size matches the number of bytes listed in header
         */
        if (file.length() < chunkSize + 8) {
            throw new WavFileException("Header chunk size (" + chunkSize
                    + ") does not match file size (" + file.length() + ")");
        }

        boolean foundFormat = false;
        boolean foundData = false;

        /*
         * Search for the Format and Data Chunks.
         */
        while (true) {
            /*
             * Read the first 8 bytes of the chunk (ID and chunk size)
             */
            bytesRead = wavFile.iStream.read(wavFile.buffer, 0, 8);
            if (bytesRead == -1) {
                throw new WavFileException("Reached end of file without finding format chunk");
            }
            if (bytesRead != 8) {
                throw new WavFileException("Could not read chunk header");
            }

            /*
             * Extract the chunk ID and Size
             */
            long chunkID = getLE(wavFile.buffer, 0, 4);
            chunkSize = getLE(wavFile.buffer, 4, 4);

            /*
             * Word align the chunk size chunkSize specifies the number of bytes holding data.
             * However, the data should be word aligned (2 bytes) so we need to calculate the actual
             * number of bytes in the chunk
             */
            long numChunkBytes = (chunkSize % 2 == 1) ? chunkSize + 1 : chunkSize;

            if (chunkID == FMT_CHUNK_ID) {
                /*
                 * Flag that the format chunk has been found
                 */
                foundFormat = true;

                /*
                 * Read in the header info
                 */
                bytesRead = wavFile.iStream.read(wavFile.buffer, 0, 16);

                /*
                 * Check this is uncompressed data
                 */
                int compressionCode = (int) getLE(wavFile.buffer, 0, 2);
                if (compressionCode != 1) {
                    throw new WavFileException(
                            "Compression Code " + compressionCode + " not supported");
                }

                /*
                 * Extract the format information
                 */
                wavFile.numChannels = (int) getLE(wavFile.buffer, 2, 2);
                wavFile.sampleRate = getLE(wavFile.buffer, 4, 4);
                wavFile.blockAlign = (int) getLE(wavFile.buffer, 12, 2);
                wavFile.validBits = (int) getLE(wavFile.buffer, 14, 2);

                if (wavFile.numChannels == 0) {
                    throw new WavFileException(
                            "Number of channels specified in header is equal to zero");
                }
                if (wavFile.blockAlign == 0) {
                    throw new WavFileException("Block Align specified in header is equal to zero");
                }
                if (wavFile.validBits < 2) {
                    throw new WavFileException("Valid Bits specified in header is less than 2");
                }
                if (wavFile.validBits > 64) {
                    throw new WavFileException(
                            "Valid Bits specified in header is greater than 64, "
                                    + "this is greater than a long can hold");
                }

                /*
                 * Calculate the number of bytes required to hold 1 sample
                 */
                wavFile.bytesPerSample = (wavFile.validBits + 7) / 8;
                if (wavFile.bytesPerSample * wavFile.numChannels != wavFile.blockAlign) {
                    throw new WavFileException(
                            "Block Align does not agree with bytes required "
                                    + "for validBits and number of channels");
                }

                /*
                 * Account for number of format bytes and then skip over any extra format bytes.
                 */
                numChunkBytes -= 16;
                if (numChunkBytes > 0) {
                    wavFile.iStream.skip(numChunkBytes);
                }
            } else if (chunkID == DATA_CHUNK_ID) {
                /*
                 * Check if we've found the format chunk. If not, throw an exception as we need the
                 * format information before we can read the data chunk
                 */
                if (foundFormat == false) {
                    throw new WavFileException("Data chunk found before Format chunk");
                }

                /*
                 * Check that the chunkSize (wav data length) is a multiple of the block align
                 * (bytes per frame)
                 */
                if (chunkSize % wavFile.blockAlign != 0) {
                    throw new WavFileException("Data Chunk size is not multiple of Block Align");
                }

                /*
                 * Calculate the number of frames
                 */
                wavFile.numFrames = chunkSize / wavFile.blockAlign;

                /*
                 * Flag that we've found the wave data chunk
                 */
                foundData = true;

                break;
            } else {
                /*
                 * If an unknown chunk ID is found, just skip over the chunk data.
                 */
                wavFile.iStream.skip(numChunkBytes);
            }
        }

        /*
         * Throw an exception if no data chunk has been found.
         */
        if (foundData == false) {
            throw new WavFileException("Did not find a data chunk");
        }

        /*
         * Calculate the scaling factor for converting to a normalized double.
         */
        if (wavFile.validBits > 8) {
            /*
             * If more than 8 validBits, data is signed. Conversion required dividing by magnitude
             * of max negative value.
             */
            wavFile.floatOffset = 0;
            wavFile.floatScale = 1 << (wavFile.validBits - 1);
        } else {
            /*
             * Else if 8 or less validBits, data is unsigned. Conversion required dividing by max
             * positive value.
             */
            wavFile.floatOffset = -1;
            wavFile.floatScale = 0.5 * ((1 << wavFile.validBits) - 1);
        }

        wavFile.bufferPointer = 0;
        wavFile.bytesRead = 0;
        wavFile.frameCounter = 0;
        wavFile.ioState = IOState.READING;

        return wavFile;
    }

    /**
     * Close the WavFile.
     *
     * @throws IOException Signals that an I/O exception has occurred
     */
    public void close() throws IOException {
        if (iStream != null) {
            iStream.close();
            iStream = null;
        }
        if (oStream != null) {
            if (bufferPointer > 0) {
                oStream.write(buffer, 0, bufferPointer);
            }
            if (wordAlignAdjust) {
                oStream.write(0);
            }
            oStream.close();
            oStream = null;
        }
        ioState = IOState.CLOSED;
    }
    /**
     * Get little-endian data from the buffer.
     *
     * @param buffer the buffer to read from
     * @param position the starting position
     * @param count the number of bytes to read
     * @return a little-endian long
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private static long getLE(final byte[] buffer, final int position, final int count) {
        int myCount = count - 1;
        int myPosition = position + myCount;
        long returnValue = buffer[myPosition] & 0xFF;
        for (int b = 0; b < myCount; b++) {
            returnValue = (returnValue << 8) + (buffer[--myPosition] & 0xFF);
        }
        return returnValue;
    }

    /**
     * Put little-endian data into the buffer.
     *
     * @param input the data to write into the buffer
     * @param buffer the buffer to write to
     * @param position the position to write to
     * @param count the number of bytes to write
     */
    @SuppressWarnings({"checkstyle:magicnumber", "unused"})
    private static void putLE(final long input, final byte[] buffer, final int position,
                              final int count) {
        long myInput = input;
        int myPosition = position;
        for (int b = 0; b < count; b++) {
            buffer[myPosition] = (byte) (input & 0xFF);
            myInput >>= 8;
            myPosition++;
        }
    }

    /**
     * Write a sample to our buffer.
     *
     * @param sample the sample to write
     * @throws IOException Signals that an I/O exception has occurred
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private void writeSample(final long sample) throws IOException {
        long mySample = sample;
        for (int b = 0; b < bytesPerSample; b++) {
            if (bufferPointer == BUFFER_SIZE) {
                oStream.write(buffer, 0, BUFFER_SIZE);
                bufferPointer = 0;
            }

            buffer[bufferPointer] = (byte) (mySample & 0xFF);
            mySample >>= 8;
            bufferPointer++;
        }
    }

    /**
     * Read a sample from the buffer.
     *
     * @return the sample read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private long readSample() throws IOException, WavFileException {
        long val = 0;

        for (int b = 0; b < bytesPerSample; b++) {
            if (bufferPointer == bytesRead) {
                int read = iStream.read(buffer, 0, BUFFER_SIZE);
                if (read == -1) {
                    throw new WavFileException("Not enough data available");
                }
                bytesRead = read;
                bufferPointer = 0;
            }

            int v = buffer[bufferPointer];
            if (b < bytesPerSample - 1 || bytesPerSample == 1) {
                v &= 0xFF;
            }
            val += v << (b * 8);

            bufferPointer++;
        }

        return val;
    }

    /**
     * Read some number of frames from the buffer into a flat int array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final int[] returnBuffer, final int count)
            throws IOException, WavFileException {
        return readFrames(returnBuffer, 0, count);
    }

    /**
     * Read some number of frames from a specific offset in the buffer into a flat int array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param offset the buffer offset to read from
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final int[] returnBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.READING) {
            throw new IOException("Cannot read from WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                returnBuffer[myOffset] = (int) readSample();
                myOffset++;
            }

            frameCounter++;
        }

        return count;
    }

    /**
     * Read some number of frames from the buffer into a multi-dimensional int array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final int[][] returnBuffer, final int count)
            throws IOException, WavFileException {
        return readFrames(returnBuffer, 0, count);
    }

    /**
     * Read some number of frames from a specific offset in the buffer into a multi-dimensional int
     * array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @param offset the buffer offset to read from
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final int[][] returnBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.READING) {
            throw new IOException("Cannot read from WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                returnBuffer[c][myOffset] = (int) readSample();
            }

            myOffset++;
            frameCounter++;
        }

        return count;
    }
    /**
     * Read some number of frames from the buffer into a flat long array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final long[] returnBuffer, final int count)
            throws IOException, WavFileException {
        return readFrames(returnBuffer, 0, count);
    }

    /**
     * Read some number of frames from a specific offset in the buffer into a flat long array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param offset the buffer offset to read from
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final long[] returnBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.READING) {
            throw new IOException("Cannot read from WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                returnBuffer[myOffset] = readSample();
                myOffset++;
            }

            frameCounter++;
        }

        return count;
    }

    /**
     * Read some number of frames from the buffer into a multi-dimensional long array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final long[][] returnBuffer, final int count)
            throws IOException, WavFileException {
        return readFrames(returnBuffer, 0, count);
    }

    /**
     * Read some number of frames from a specific offset in the buffer into a multi-dimensional long
     * array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @param offset the buffer offset to read from
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final long[][] returnBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.READING) {
            throw new IOException("Cannot read from WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                returnBuffer[c][myOffset] = readSample();
            }

            myOffset++;
            frameCounter++;
        }

        return count;
    }
    /**
     * Read some number of frames from the buffer into a flat double array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final double[] returnBuffer, final int count)
            throws IOException, WavFileException {
        return readFrames(returnBuffer, 0, count);
    }

    /**
     * Read some number of frames from a specific offset in the buffer into a flat double array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param offset the buffer offset to read from
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final double[] returnBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.READING) {
            throw new IOException("Cannot read from WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                returnBuffer[myOffset] = floatOffset + (double) readSample() / floatScale;
                myOffset++;
            }

            frameCounter++;
        }

        return count;
    }

    /**
     * Read some number of frames from the buffer into a multi-dimensional double array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final double[][] returnBuffer, final int count)
            throws IOException, WavFileException {
        return readFrames(returnBuffer, 0, count);
    }

    /**
     * Read some number of frames from a specific offset in the buffer into a multi-dimensional
     * double array.
     *
     * @param returnBuffer the buffer to read samples into
     * @param count the number of frames to read
     * @param offset the buffer offset to read from
     * @return the number of frames read
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int readFrames(final double[][] returnBuffer, final int offset,
                          final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.READING) {
            throw new IOException("Cannot read from WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                returnBuffer[c][myOffset] = floatOffset + (double) readSample() / floatScale;
            }

            myOffset++;
            frameCounter++;
        }

        return count;
    }

    /**
     * Write some number of frames into the buffer from a flat int array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final int[] sampleBuffer, final int count)
            throws IOException, WavFileException {
        return writeFrames(sampleBuffer, 0, count);
    }

    /**
     * Write some number of frames into the buffer at a specific offset from a flat int array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @param offset the buffer offset to write into
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final int[] sampleBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.WRITING) {
            throw new IOException("Cannot write to WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                writeSample(sampleBuffer[myOffset]);
                myOffset++;
            }

            frameCounter++;
        }

        return count;
    }

    /**
     * Write some number of frames into the buffer from a multi-dimensional int array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final int[][] sampleBuffer, final int count)
            throws IOException, WavFileException {
        return writeFrames(sampleBuffer, 0, count);
    }

    /**
     * Write some number of frames into the buffer at a specific offset from a multi-dimensional int
     * array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @param offset the buffer offset to write into
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final int[][] sampleBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.WRITING) {
            throw new IOException("Cannot write to WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                writeSample(sampleBuffer[c][myOffset]);
            }

            myOffset++;
            frameCounter++;
        }

        return count;
    }

    /**
     * Write some number of frames into the buffer from a flat long array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final long[] sampleBuffer, final int count)
            throws IOException, WavFileException {
        return writeFrames(sampleBuffer, 0, count);
    }

    /**
     * Write some number of frames into the buffer at a specific offset from a flat long array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @param offset the buffer offset to write into
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final long[] sampleBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.WRITING) {
            throw new IOException("Cannot write to WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                writeSample(sampleBuffer[myOffset]);
                myOffset++;
            }

            frameCounter++;
        }

        return count;
    }

    /**
     * Write some number of frames into the buffer from a multi-dimensional long array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final long[][] sampleBuffer, final int count)
            throws IOException, WavFileException {
        return writeFrames(sampleBuffer, 0, count);
    }

    /**
     * Write some number of frames into the buffer at a specific offset from a multi-dimensional
     * long array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @param offset the buffer offset to write into
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final long[][] sampleBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.WRITING) {
            throw new IOException("Cannot write to WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                writeSample(sampleBuffer[c][myOffset]);
            }

            myOffset++;
            frameCounter++;
        }

        return count;
    }

    /**
     * Write some number of frames into the buffer from a flat double array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final double[] sampleBuffer, final int count)
            throws IOException, WavFileException {
        return writeFrames(sampleBuffer, 0, count);
    }

    /**
     * Write some number of frames into the buffer at a specific offset from a flat double array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @param offset the buffer offset to write into
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final double[] sampleBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.WRITING) {
            throw new IOException("Cannot write to WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                writeSample((long) (floatScale * (floatOffset + sampleBuffer[myOffset])));
                myOffset++;
            }

            frameCounter++;
        }

        return count;
    }

    /**
     * Write some number of frames into the buffer from a multi-dimensional double array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final double[][] sampleBuffer, final int count)
            throws IOException, WavFileException {
        return writeFrames(sampleBuffer, 0, count);
    }

    /**
     * Write some number of frames into the buffer at a specific offset from a multi-dimensional
     * double array.
     *
     * @param sampleBuffer the buffer to read samples from
     * @param count the number of frames to write
     * @param offset the buffer offset to write into
     * @return the number of frames written
     * @throws IOException Signals that an I/O exception has occurred
     * @throws WavFileException a WavFile-specific exception
     */
    public int writeFrames(final double[][] sampleBuffer, final int offset, final int count)
            throws IOException, WavFileException {
        int myOffset = offset;
        if (ioState != IOState.WRITING) {
            throw new IOException("Cannot write to WavFile instance");
        }

        for (int f = 0; f < count; f++) {
            if (frameCounter == numFrames) {
                return f;
            }

            for (int c = 0; c < numChannels; c++) {
                writeSample((long) (floatScale * (floatOffset + sampleBuffer[c][myOffset])));
            }

            myOffset++;
            frameCounter++;
        }

        return count;
    }
}