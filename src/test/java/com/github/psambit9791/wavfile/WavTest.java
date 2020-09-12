package com.github.psambit9791.wavfile;

import java.io.File;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;

public class WavTest {

    File fread = new File("test_inputs/music.wav");
    File fwrite = new File("test_outputs/out.wav");


    @Test
    @Order(1)
    public void createTestOutputDirectory() {
        String dirName = "./test_outputs/";
        File directory = new File(dirName);
        if (! directory.exists()){
            directory.mkdir();
        }
    }

    @Test
    public void readWav() throws WavFileException, IOException {
        // Open the wav file specified as the first argument
        WavFile wavFile = WavFile.openWavFile(this.fread);

        // Get the number of audio channels in the wav file
        int numChannels = wavFile.getNumChannels();

        // Create a buffer of 100 frames
        int[] buffer = new int[100 * numChannels];

        int framesRead;
        int resMin = -15559;
        int resMax = 15063;

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        do
        {
            // Read frames into buffer
            framesRead = wavFile.readFrames(buffer, 100);

            // Loop through frames and look for minimum and maximum value
            for (int s=0 ; s<framesRead * numChannels ; s++)
            {
                if (buffer[s] > max) max = buffer[s];
                if (buffer[s] < min) min = buffer[s];
            }
        }
        while (framesRead != 0);

        // Close the wavFile
        wavFile.close();

        Assertions.assertEquals(min, resMin);
        Assertions.assertEquals(max, resMax);
    }

    @Test
    public void writeWav() throws WavFileException, IOException {
        int sampleRate = 44100;		// Samples per second
        double duration = 5.0;		// Seconds

        // Calculate the number of frames required for specified duration
        long numFrames = (long)(duration * sampleRate);

        // Create a wav file with the name specified as the first argument
        WavFile wavFile = WavFile.newWavFile(this.fwrite, 2, numFrames, 16, sampleRate);

        // Create a buffer of 100 frames
        double[][] buffer = new double[2][100];

        // Initialise a local frame counter
        long frameCounter = 0;

        // Loop until all frames written
        while (frameCounter < numFrames)
        {
            // Determine how many frames to write, up to a maximum of the buffer size
            long remaining = wavFile.getFramesRemaining();
            int toWrite = (remaining > 100) ? 100 : (int) remaining;

            // Fill the buffer, one tone per channel
            for (int s=0 ; s<toWrite ; s++, frameCounter++)
            {
                buffer[0][s] = Math.sin(2.0 * Math.PI * 400 * frameCounter / sampleRate);
                buffer[1][s] = Math.sin(2.0 * Math.PI * 500 * frameCounter / sampleRate);
            }

            // Write the buffer
            wavFile.writeFrames(buffer, toWrite);
        }

        // Close the wavFile
        wavFile.close();

        boolean fileExists = new File("test_outputs/out.wav").exists();
        Assertions.assertTrue(fileExists);
    }

}
