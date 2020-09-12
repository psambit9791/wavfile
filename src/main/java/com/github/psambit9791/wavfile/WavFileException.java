package com.github.psambit9791.wavfile;

/**
 * WavFile-specific exception class.
 */
public class WavFileException extends Exception
{
    /**
     * Create a new WavFile-specific exception.
     */
    public WavFileException()
    {
        super();
    }

    /**
     * Create a new WavFile-specific exception with a given message.
     *
     * @param message the message
     */
    public WavFileException(String message)
    {
        super(message);
    }

    /**
     * Create a new WavFile-specific exception with a message and throwable exception.
     *
     * @param message the message
     * @param cause the cause
     */
    public WavFileException(String message, Throwable cause)
    {
        super(message, cause);
    }

    /**
     * Create a new WavFile-specific exception with a throwable exception.
     *
     * @param cause the cause
     */
    public WavFileException(Throwable cause)
    {
        super(cause);
    }
}