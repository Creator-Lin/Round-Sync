package ca.pkay.rcloneexplorer;

import java.io.File;
import java.util.Comparator;

import ca.pkay.rcloneexplorer.Items.FileItem;

public class FileComparators {

    /**
     * Compares names using natural numeric ordering. Consecutive digit runs are compared by
     * numeric value instead of character value, so 1, 2, 3, 10, 100 are ordered as users expect.
     * The comparison is case-insensitive and does not parse digit runs into primitive integers,
     * which avoids overflow for very long file names.
     */
    static int compareNaturalNames(String left, String right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }

        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex);
            char rightChar = right.charAt(rightIndex);

            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftRunEnd = findDigitRunEnd(left, leftIndex);
                int rightRunEnd = findDigitRunEnd(right, rightIndex);
                int leftSignificantStart = skipLeadingZeros(left, leftIndex, leftRunEnd);
                int rightSignificantStart = skipLeadingZeros(right, rightIndex, rightRunEnd);
                int leftSignificantLength = leftRunEnd - leftSignificantStart;
                int rightSignificantLength = rightRunEnd - rightSignificantStart;

                if (leftSignificantLength != rightSignificantLength) {
                    return Integer.compare(leftSignificantLength, rightSignificantLength);
                }

                for (int offset = 0; offset < leftSignificantLength; offset++) {
                    char leftDigit = left.charAt(leftSignificantStart + offset);
                    char rightDigit = right.charAt(rightSignificantStart + offset);
                    if (leftDigit != rightDigit) {
                        return Character.compare(leftDigit, rightDigit);
                    }
                }

                // Equal numeric values: put the shorter representation first (1 before 01).
                int leftRunLength = leftRunEnd - leftIndex;
                int rightRunLength = rightRunEnd - rightIndex;
                if (leftRunLength != rightRunLength) {
                    return Integer.compare(leftRunLength, rightRunLength);
                }

                leftIndex = leftRunEnd;
                rightIndex = rightRunEnd;
                continue;
            }

            int foldedComparison = Character.compare(
                    Character.toLowerCase(leftChar),
                    Character.toLowerCase(rightChar));
            if (foldedComparison != 0) {
                return foldedComparison;
            }
            leftIndex++;
            rightIndex++;
        }

        if (leftIndex != left.length() || rightIndex != right.length()) {
            return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
        }

        // Keep the result deterministic when two names only differ by case.
        return left.compareTo(right);
    }

    private static int findDigitRunEnd(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int skipLeadingZeros(String value, int start, int end) {
        int index = start;
        while (index < end - 1 && value.charAt(index) == '0') {
            index++;
        }
        return index;
    }

    public static class SortAlphaDescending implements Comparator<FileItem> {

        @Override
        public int compare(FileItem fileItem, FileItem other) {
            int directoryOrder = compareDirectoryFirst(fileItem.isDir(), other.isDir());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return compareNaturalNames(other.getName(), fileItem.getName());
        }
    }

    public static class SortAlphaAscending implements Comparator<FileItem> {

        @Override
        public int compare(FileItem fileItem, FileItem other) {
            int directoryOrder = compareDirectoryFirst(fileItem.isDir(), other.isDir());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return compareNaturalNames(fileItem.getName(), other.getName());
        }
    }

    public static class SortSizeDescending implements Comparator<FileItem> {

        @Override
        public int compare(FileItem fileItem, FileItem other) {
            int directoryOrder = compareDirectoryFirst(fileItem.isDir(), other.isDir());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            if (fileItem.isDir()) {
                return compareNaturalNames(fileItem.getName(), other.getName());
            }
            return Long.compare(other.getSize(), fileItem.getSize());
        }
    }

    public static class SortSizeAscending implements Comparator<FileItem> {

        @Override
        public int compare(FileItem fileItem, FileItem other) {
            int directoryOrder = compareDirectoryFirst(fileItem.isDir(), other.isDir());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            if (fileItem.isDir()) {
                return compareNaturalNames(fileItem.getName(), other.getName());
            }
            return Long.compare(fileItem.getSize(), other.getSize());
        }
    }

    public static class SortModTimeDescending implements Comparator<FileItem> {

        @Override
        public int compare(FileItem fileItem, FileItem other) {
            int directoryOrder = compareDirectoryFirst(fileItem.isDir(), other.isDir());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return Long.compare(other.getModTime(), fileItem.getModTime());
        }
    }

    public static class SortModTimeAscending implements Comparator<FileItem> {

        @Override
        public int compare(FileItem fileItem, FileItem other) {
            int directoryOrder = compareDirectoryFirst(fileItem.isDir(), other.isDir());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return Long.compare(fileItem.getModTime(), other.getModTime());
        }
    }

    public static class SortFileAlphaDescending implements Comparator<File> {

        @Override
        public int compare(File file, File other) {
            int directoryOrder = compareDirectoryFirst(file.isDirectory(), other.isDirectory());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return compareNaturalNames(other.getName(), file.getName());
        }
    }

    public static class SortFileAlphaAscending implements Comparator<File> {

        @Override
        public int compare(File file, File other) {
            int directoryOrder = compareDirectoryFirst(file.isDirectory(), other.isDirectory());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return compareNaturalNames(file.getName(), other.getName());
        }
    }

    public static class SortFileSizeDescending implements Comparator<File> {

        @Override
        public int compare(File file, File other) {
            int directoryOrder = compareDirectoryFirst(file.isDirectory(), other.isDirectory());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            if (file.isDirectory()) {
                return compareNaturalNames(file.getName(), other.getName());
            }
            return Long.compare(other.length(), file.length());
        }
    }

    public static class SortFileSizeAscending implements Comparator<File> {

        @Override
        public int compare(File file, File other) {
            int directoryOrder = compareDirectoryFirst(file.isDirectory(), other.isDirectory());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            if (file.isDirectory()) {
                return compareNaturalNames(file.getName(), other.getName());
            }
            return Long.compare(file.length(), other.length());
        }
    }

    public static class SortFileModTimeDescending implements Comparator<File> {

        @Override
        public int compare(File file, File other) {
            int directoryOrder = compareDirectoryFirst(file.isDirectory(), other.isDirectory());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return Long.compare(other.lastModified(), file.lastModified());
        }
    }

    public static class SortFileModTimeAscending implements Comparator<File> {

        @Override
        public int compare(File file, File other) {
            int directoryOrder = compareDirectoryFirst(file.isDirectory(), other.isDirectory());
            if (directoryOrder != 0) {
                return directoryOrder;
            }
            return Long.compare(file.lastModified(), other.lastModified());
        }
    }

    private static int compareDirectoryFirst(boolean leftDirectory, boolean rightDirectory) {
        if (leftDirectory == rightDirectory) {
            return 0;
        }
        return leftDirectory ? -1 : 1;
    }
}
