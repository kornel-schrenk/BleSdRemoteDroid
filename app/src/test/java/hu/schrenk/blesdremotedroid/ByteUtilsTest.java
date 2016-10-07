package hu.schrenk.blesdremotedroid;

import org.junit.Test;

import hu.schrenk.blesdremotedroid.util.ByteUtils;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

public class ByteUtilsTest {

    @Test
    public void testContains() {
        byte[] array = { 64, 72, 69, 76, 76, 79, 35}; //@HELLO#
        assertFalse(ByteUtils.contains(array, (byte)42));
        assertTrue(ByteUtils.contains(array, (byte)35));
    }

    @Test
    public void testIndexOf() {
        byte[] array = { 64, 72, 69, 76, 76, 79, 35}; //@HELLO#
        assertTrue(ByteUtils.indexOf(array, (byte)35) == 6);
        assertTrue(ByteUtils.indexOf(array, (byte)42) == -1);
    }

    @Test
    public void testSubByteArray() {
        byte[] array = { 64, 72, 69, 76, 76, 79, 35}; //@HELLO#
        byte[] output = ByteUtils.subByteArray(array, ByteUtils.indexOf(array, (byte)69));
        assertTrue(output.length == 4);
        output = ByteUtils.subByteArray(array, ByteUtils.indexOf(array, (byte)64));
        assertTrue(output.length == array.length-1);
        output = ByteUtils.subByteArray(array, ByteUtils.indexOf(array, (byte)35));
        assertTrue(output.length == 0);
    }
}
