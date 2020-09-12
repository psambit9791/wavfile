package com.github.psambit9791.wavfile;

import java.io.File;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;

public class WavTest {

    File f = new File("test_inputs/music.wav");

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

    }

}
