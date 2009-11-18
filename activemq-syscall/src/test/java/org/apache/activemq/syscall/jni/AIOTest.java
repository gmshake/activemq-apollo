/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package org.apache.activemq.syscall.jni;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.activemq.syscall.NativeAllocation;
import org.apache.activemq.syscall.jni.AIO;
import org.apache.activemq.syscall.jni.AIO.aiocb;
import org.junit.Test;

import static org.apache.activemq.syscall.jni.AIO.*;
import static org.apache.activemq.syscall.jni.CLibrary.*;
import static org.apache.activemq.syscall.jni.IO.*;

import static org.apache.activemq.syscall.NativeAllocation.*;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/**
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 *
 */
public class AIOTest {
    
    @Test
    public void write() throws IOException, InterruptedException {
    	assumeThat(AIO.SUPPORTED, is(true));
    	 
        File file = new File("target/test-data/test.data");
        file.getParentFile().mkdirs();

        // Setup a buffer holds the data that we will be writing..
        StringBuffer sb = new StringBuffer();
        for( int i=0; i < 1024*4; i++ ) {
            sb.append((char)('a'+(i%26)));
        }
                
        String expected = sb.toString();
        NativeAllocation writeBuffer = allocate(expected);

        long aiocbp = malloc(aiocb.SIZEOF);
        System.out.println("Allocated cb of size: "+aiocb.SIZEOF);

        try {
            // open the file...
            int mode = S_IRUSR|S_IWUSR|S_IRGRP|S_IROTH;
            int fd = open(file.getCanonicalPath(), O_NONBLOCK | O_CREAT | O_TRUNC| O_RDWR, mode);
            checkrc(fd);
            
            // Create a control block..
            // The where:
            aiocb cb = new aiocb();
            cb.aio_fildes = fd;
            cb.aio_offset = 0;
            // The what:
            cb.aio_buf = writeBuffer.pointer();        
            cb.aio_nbytes = writeBuffer.length();
            
            // Move the struct into the c heap.
            aiocb.memmove(aiocbp, cb, aiocb.SIZEOF);

            // enqueue the async write..
            checkrc(aio_write(aiocbp));
            
            long blocks[] = new long[]{aiocbp};
            
            // Wait for the IO to complete.
            long timeout = NULL; // To suspend forever.
            checkrc(aio_suspend(blocks, blocks.length, timeout));
            
            // Check to see if it completed.. it should 
            // since we previously suspended.
            int rc = aio_error(aiocbp);
            checkrc(rc);
            assertEquals(0, rc);

            // The full buffer should have been written.
            long count = aio_return(aiocbp);
            assertEquals(count, writeBuffer.length());
            
            checkrc(close(fd));
            
        } finally {
            // Lets free up allocated memory..
            writeBuffer.free();
            if( aiocbp!=NULL ) {
                free(aiocbp);
            }
        }
        
        // Read the file in and verify the contents is what we expect 
        String actual = loadContent(file);
        assertEquals(expected, actual);
    }

    private String loadContent(File file) throws FileNotFoundException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FileInputStream is = new FileInputStream(file);
        try {
            int c=0;
            while( (c=is.read())>=0 ) {
                baos.write(c);
            }
        } finally {
            is.close();
        }
        String actual = new String(baos.toByteArray());
        return actual;
    }

    private void storeContent(File file, String content) throws FileNotFoundException, IOException {
        FileOutputStream os = new FileOutputStream(file);
        try {
            os.write(content.getBytes());
        } finally {
            os.close();
        }
    }
    
    private void checkrc(int rc) throws IOException {
        if( rc==-1 ) {
            throw new IOException("IO failure: "+string(strerror(errno())));
        }
    }

    @Test
    public void testFree() {
        long ptr = malloc(100);
        free(ptr);
    }
    
}
