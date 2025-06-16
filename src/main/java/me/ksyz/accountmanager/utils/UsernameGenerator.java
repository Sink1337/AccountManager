package me.ksyz.accountmanager.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Random;

public class UsernameGenerator {
    private static final String LOCAL_FILE_PATH = "/usernames.txt";

    private static final Random RANDOM = new Random();

    public static String[] retrieve() {
        try {
            InputStream stream = UsernameGenerator.class.getResourceAsStream(LOCAL_FILE_PATH);

            if (stream == null) {
                System.err.println("Error: Local username file not found! Please ensure " + LOCAL_FILE_PATH + " exists in the resources directory.");
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    builder.append(line.trim()).append(System.lineSeparator());
                }
            }
            reader.close();

            if (builder.length() == 0) {
                System.err.println("Error: Local username file " + LOCAL_FILE_PATH + " is empty or contains no valid content.");
                return null;
            }

            return builder.toString().split(System.lineSeparator());

        } catch (IOException ex) {
            System.err.println("Error reading local username file: " + ex.getMessage());
            ex.printStackTrace();
        }

        return null;
    }

    public static String generate() {
        String[] generated = generate(1);
        return (generated == null || generated.length == 0) ? null : generated[0];
    }

    public static String[] generate(int amount) {
        String[] usernames = retrieve();
        if (usernames == null || usernames.length == 0) {
            return null;
        }

        List<String> acceptableUsernames = Arrays.stream(usernames)
                .filter(username -> username.length() >= 3 && username.length() <= 6)
                .collect(Collectors.toList());

        if (acceptableUsernames.isEmpty()) {
            System.err.println("Warning: No words matching the length criteria (3-6 characters) found in the local username file.");
            return null;
        }

        String[] generated = new String[amount];
        for (int i = 0; i < amount; ++i) {
            String prefix = acceptableUsernames.get(RANDOM.nextInt(acceptableUsernames.size()));
            String suffix = acceptableUsernames.get(RANDOM.nextInt(acceptableUsernames.size()));
            String username = applyPattern(prefix, suffix);
            username = applyPattern(username);
            if (username.length() > 16) {
                username = username.substring(0, 16);
            }
            generated[i] = username;
        }

        return generated;
    }

    private static String applyPattern(String prefix, String suffix) {
        int pattern = RANDOM.nextInt(4);
        switch (pattern) {
            case 0: {
                return prefix + "_" + suffix;
            }
            case 1: {
                String sfxPart = suffix.length() >= 2 ? suffix.substring(0, 2) : suffix;
                return prefix + sfxPart + RANDOM.nextInt(100);
            }
            case 2: {
                int index = RANDOM.nextInt(Math.min(prefix.length(), suffix.length()) + 1);
                return prefix.substring(0, index) + "_" + suffix.substring(index);
            }
            case 3: {
                StringBuilder merge = new StringBuilder(prefix).append(suffix);
                if (merge.length() == 0) return "";
                int uIndex = RANDOM.nextInt(merge.length() + 1);
                int nIndex = RANDOM.nextInt(merge.length() + 1);
                if (uIndex < nIndex) {
                    merge.insert(nIndex, RANDOM.nextInt(100));
                    merge.insert(uIndex, "_");
                } else {
                    merge.insert(uIndex, "_");
                    merge.insert(nIndex, RANDOM.nextInt(100));
                }
                return merge.toString();
            }
            default: {
                return prefix + suffix;
            }
        }
    }

    private static String applyPattern(String username) {
        if (username == null || username.isEmpty()) {
            return username;
        }

        double numberChance = 0.125;
        double upperChance = 0.25;

        char[] chars = username.toCharArray();
        for (int i = 0; i < chars.length; ++i) {
            char c = chars[i];
            if (Character.isLetter(c) && (i == 0 || (chars[i - 1] == '_' || Character.isDigit(chars[i - 1])))) {
                if (RANDOM.nextDouble() < upperChance) {
                    chars[i] = Character.toUpperCase(c);
                    continue;
                }
            }

            char lower = Character.toLowerCase(c);
            char replacement = getReplacement(lower);
            if (replacement != lower) {
                if (RANDOM.nextDouble() < numberChance) {
                    chars[i] = replacement;
                    numberChance *= 0.5;
                }
            }
        }

        return new String(chars);
    }

    private static char getReplacement(char c) {
        if (c == 'a') {
            return '4';
        } else if (c == 'e') {
            return '3';
        } else if (c == 'i') {
            return '1';
        } else if (c == 'o') {
            return '0';
        } else if (c == 't') {
            return '7';
        } else if (c == 's') {
            return '5';
        } else {
            return c;
        }
    }
}