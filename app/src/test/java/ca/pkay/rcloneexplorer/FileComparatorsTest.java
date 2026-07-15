package ca.pkay.rcloneexplorer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FileComparatorsTest {

    @Test
    public void naturalNameComparisonOrdersNumericRunsByValue() {
        List<String> names = new ArrayList<>(Arrays.asList("100", "10", "3", "2", "1"));
        Collections.sort(names, FileComparators::compareNaturalNames);
        assertEquals(Arrays.asList("1", "2", "3", "10", "100"), names);
    }

    @Test
    public void naturalNameComparisonWorksInsideFileNames() {
        List<String> names = new ArrayList<>(Arrays.asList(
                "image100.jpg", "image10.jpg", "image3.jpg", "image2.jpg", "image1.jpg"));
        Collections.sort(names, FileComparators::compareNaturalNames);
        assertEquals(Arrays.asList(
                "image1.jpg", "image2.jpg", "image3.jpg", "image10.jpg", "image100.jpg"), names);
    }

    @Test
    public void naturalNameComparisonDoesNotOverflowLongNumbers() {
        assertTrue(FileComparators.compareNaturalNames(
                "file999999999999999999999", "file1000000000000000000000") < 0);
    }

    @Test
    public void naturalNameComparisonPlacesShorterEqualNumberFirst() {
        assertTrue(FileComparators.compareNaturalNames("file1", "file01") < 0);
    }
}
