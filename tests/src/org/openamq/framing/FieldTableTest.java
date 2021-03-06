package org.openamq.framing;

import org.apache.mina.common.ByteBuffer;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Date;

import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;

public class FieldTableTest
{
    @Test
    public void dataDump() throws IOException, AMQFrameDecodingException
    {
        byte[] data = readBase64("content.txt");
        System.out.println("Got " + data.length + " bytes of data");
        for(int i = 0; i < 100; i++)
        {
            System.out.print((char) data[i]);
        }
        System.out.println();
        int size = 4194304;
        ByteBuffer buffer = ByteBuffer.allocate(data.length);
        buffer.put(data);
        buffer.flip();
        FieldTable table = new FieldTable(buffer, size);
    }

    /*
    @Test
    public void case1() throws AMQFrameDecodingException, IOException
    {
        testEncoding(load("FieldTableTest.properties"));
    }

    @Test
    public void case2() throws AMQFrameDecodingException, IOException
    {
        testEncoding(load("FieldTableTest2.properties"));
    }
    */
    void testEncoding(FieldTable table) throws AMQFrameDecodingException
    {
        assertEquivalent(table, encodeThenDecode(table));
    }

    public void assertEquivalent(FieldTable table1, FieldTable table2)
    {
        for(Iterator i = table1.keySet().iterator(); i.hasNext();)
        {
            String key = (String) i.next();
            assertEquals("Values for " + key + " did not match", table1.get(key), table2.get(key));
            //System.out.println("Values for " + key + " matched (" + table1.get(key) + ")");
        }
    }

    FieldTable encodeThenDecode(FieldTable table) throws AMQFrameDecodingException
    {
        ContentHeaderBody header = new ContentHeaderBody();
        header.classId = 6;
        BasicContentHeaderProperties properties = new BasicContentHeaderProperties();
        header.properties = properties;

        properties.setHeaders(table);
        int size = header.getSize();

        //encode
        ByteBuffer buffer = ByteBuffer.allocate(size);
        header.writePayload(buffer);

        //decode
        buffer.flip();

        header = new ContentHeaderBody();
        header.populateFromBuffer(buffer, size);

        return ((BasicContentHeaderProperties) header.properties).getHeaders();
    }

    byte[] readBase64(String name) throws IOException
    {
        String content = read(new InputStreamReader(getClass().getResourceAsStream(name)));
        return Base64.decode(content);
    }

    public FieldTable load(String name) throws IOException
    {
        return populate(new FieldTable(), read(name));
    }

    Properties read(String name) throws IOException
    {
        Properties p = new Properties();
        p.load(getClass().getResourceAsStream(name));
        return p;
    }

    FieldTable populate(FieldTable table, Properties properties)
    {
        for(Enumeration i = properties.propertyNames(); i.hasMoreElements();)
        {
            String key = (String) i.nextElement();
            String value = properties.getProperty(key);
            try{
                int ival = Integer.parseInt(value);
                table.put(key, new Long(ival));
            }
            catch(NumberFormatException e)
            {
                table.put(key, value);
            }
        }
        return table;
    }

    static String read(Reader in) throws IOException{
        return read(in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in));
    }

    static String read(BufferedReader in) throws IOException{
        StringBuffer buffer = new StringBuffer();
        String line = in.readLine();
        while(line != null){
            buffer.append(line + " ");
            line = in.readLine();
        }
        return buffer.toString();
    }

}
