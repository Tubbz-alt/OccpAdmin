package edu.uri.dfcsc.occp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import de.waldheinz.fs.FsFile;

/**
 * Wrap a {@link FsFile} so we can treat it like an {@link OutputStream}
 * 
 * @author Kevin Bryan
 */
public class FsFileAdapter extends OutputStream {
    private final FsFile file;
    private int offset;

    /**
     * @param f {@link FsFile} to wrap
     */
    public FsFileAdapter(FsFile f) {
        file = f;
        offset = 0;
        ByteBuffer.allocate(1024);
    }

    @Override
    public void close() throws IOException {
        flush();
    }

    @Override
    public void write(int b) throws IOException {
        byte bb[] = new byte[1];
        bb[0] = (byte) b;
        file.write(offset, ByteBuffer.wrap(bb));
        ++offset;
    }

    @Override
    public void write(byte[] b) throws IOException {
        file.write(offset, ByteBuffer.wrap(b));
    }

    @Override
    public void flush() throws IOException {
        file.flush();
    }
}
