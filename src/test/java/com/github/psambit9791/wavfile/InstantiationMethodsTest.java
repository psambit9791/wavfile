package com.github.psambit9791.wavfile;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class InstantiationMethodsTest {

    @Test
    @Order(1)
    public void createTestOutputDirectory() {
        String dirName = "./test_outputs/";
        File directory = new File(dirName);
        if (!directory.exists()) {
            directory.mkdir();
        }
    }

    @Test
    public void readingTests() throws WavFileException, IOException {
        String inputPath = "test_inputs/music.wav";

        // Open the wav file specified as the first argument by FileInputStream
        FileInputStream fin = new FileInputStream(inputPath);
        WavFile fisWavFile = WavFile.openInputStream(fin);
        readWav(fisWavFile);

        // Open the wav file specified as the first argument by FileDescriptor
        FileInputStream fdFin = new FileInputStream(inputPath);
        FileDescriptor fdread = fdFin.getFD();
        WavFile fdWavFile = WavFile.openWavFileDescrptor(fdread);
        readWav(fdWavFile);

        // Open the wav file specified as the first argument by File
        File fread = new File(inputPath);
        WavFile fWavFile = WavFile.openWavFile(fread);
        readWav(fWavFile);

    }

    @Test
    public void writingTests() throws WavFileException, IOException {

        int sampleRate = 44100; // Samples per second
        double duration = 5.0; // Seconds

        // Calculate the number of frames required for specified duration
        long numFrames = (long) (duration * sampleRate);
        // file paths for different methods
        String fdPath = "test_outputs/out_fd.wav";
        String fPath = "test_outputs/out_f.wav";
        String fosPath = "test_outputs/out_fos.wav";

        // Create a wav file with FileDescriptor
        FileOutputStream fout = new FileOutputStream(fdPath);
        WavFile fdWavFile = WavFile.newWavFile(fout.getFD(), 2, numFrames, 16, sampleRate);
        writeWav(fdWavFile);
        fout.close();

        // Create a wav file with FileDescriptor
        File fwrite = new File(fPath);
        WavFile fWavFile = WavFile.newWavFile(fwrite, 2, numFrames, 16, sampleRate);
        writeWav(fWavFile);

        // Create a wav file with FileOutputStream
        FileOutputStream foswrite = new FileOutputStream(fosPath);
        WavFile fosWavFile = WavFile.newWavFile(foswrite, 2, numFrames, 16, sampleRate);
        writeWav(fosWavFile);

        File fOut = new File(fPath);
        File fdOut = new File(fdPath);
        File fosOut = new File(fosPath);

        // Check if files ae created
        Assertions.assertTrue(fOut.exists());
        Assertions.assertTrue(fOut.exists());
        Assertions.assertTrue(fosOut.exists());

        // Check if files have the same size
        Assertions.assertEquals(fOut.length(), fdOut.length());
        Assertions.assertEquals(fOut.length(), fosOut.length());

    }

    private void readWav(WavFile wavFile) throws IOException, WavFileException {

        // Get the number of audio channels in the wav file
        int numChannels = wavFile.getNumChannels();

        // Create a buffer of 100 frames
        int[] buffer = new int[100 * numChannels];

        int framesRead;
        int resMin = -15559;
        int resMax = 15063;

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        do {
            // Read frames into buffer
            framesRead = wavFile.readFrames(buffer, 100);

            // Loop through frames and look for minimum and maximum value
            for (int s = 0; s < framesRead * numChannels; s++) {
                if (buffer[s] > max)
                    max = buffer[s];
                if (buffer[s] < min)
                    min = buffer[s];
            }
        } while (framesRead != 0);

        // Close the wavFile
        wavFile.close();

        Assertions.assertEquals(min, resMin);
        Assertions.assertEquals(max, resMax);
        Assertions.assertEquals(wavFile.getNumChannels(), 2);
        Assertions.assertEquals(wavFile.getNumFrames(), 1208718);
        Assertions.assertEquals(wavFile.getSampleRate(), 44100);
        Assertions.assertEquals(wavFile.getValidBits(), 16);
    }

    public void writeWav(WavFile wavFile) throws WavFileException, IOException {

        // Create a buffer of 100 frames
        double[][] buffer = new double[2][100];

        // Initialise a local frame counter
        long frameCounter = 0;

        // Loop until all frames written
        while (frameCounter < wavFile.getNumFrames()) {
            // Determine how many frames to write, up to a maximum of the buffer size
            long remaining = wavFile.getFramesRemaining();
            int toWrite = (remaining > 100) ? 100 : (int) remaining;

            // Fill the buffer, one tone per channel
            for (int s = 0; s < toWrite; s++, frameCounter++) {
                buffer[0][s] = Math.sin(2.0 * Math.PI * 400 * frameCounter / wavFile.getSampleRate());
                buffer[1][s] = Math.sin(2.0 * Math.PI * 500 * frameCounter / wavFile.getSampleRate());
            }

            // Write the buffer
            wavFile.writeFrames(buffer, toWrite);
        }

        // Close the wavFile
        wavFile.close();

    }

}
