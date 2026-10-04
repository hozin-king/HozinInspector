package com.hozinking.appinspector.ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Parser DEX minimal tanpa library tambahan.
 * - listClasses(apkPath): semua nama class (com.foo.Bar) dari semua classes*.dex
 * - listMethods(apkPath, className): method + signature + dex method index
 *
 * Catatan jujur: Java/Kotlin TIDAK punya "address offset" seperti native.
 * Yang bisa ditampilkan hanyalah dex index + signature — bukan offset memori.
 */
public class DexParser {

    public static class MethodInfo {
        public final String name;
        public final String signature; // contoh: onCreate(Bundle): void
        public final int dexIndex;     // index di method_ids (bukan address!)
        public MethodInfo(String n, String s, int i) {
            name = n;
            signature = s;
            dexIndex = i;
        }
    }

    /** Semua nama class biner (com.foo.Bar, termasuk inner class com.foo.Bar$Inner). */
    public static List<String> listClasses(String apkPath) throws Exception {
        List<String> out = new ArrayList<>();
        ZipFile zf = new ZipFile(new File(apkPath));
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                byte[] dex = readAll(zf.getInputStream(e));
                parseClasses(dex, out);
            }
        } finally {
            try {
                zf.close();
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Method milik satu class: nama + signature readable + dex method index. */
    public static List<MethodInfo> listMethods(String apkPath, String className) throws Exception {
        String target = "L" + className.replace('.', '/') + ";";
        List<MethodInfo> out = new ArrayList<>();
        ZipFile zf = new ZipFile(new File(apkPath));
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                byte[] dex = readAll(zf.getInputStream(e));
                parseMethods(dex, target, out);
            }
        } finally {
            try {
                zf.close();
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    // ---------- parsing ----------

    private static void parseClasses(byte[] dex, List<String> out) {
        if (dex.length < 0x70) return;
        int stringIdsSize = u32(dex, 0x38);
        int stringIdsOff = u32(dex, 0x3C);
        int typeIdsSize = u32(dex, 0x40);
        int typeIdsOff = u32(dex, 0x44);
        int classDefsSize = u32(dex, 0x60);
        int classDefsOff = u32(dex, 0x64);
        if (classDefsSize == 0 || !inRange(dex, classDefsOff, classDefsSize * 32)) return;
        String[] strings = readStrings(dex, stringIdsSize, stringIdsOff);
        if (strings == null) return;
        for (int i = 0; i < classDefsSize; i++) {
            int classIdx = u32(dex, classDefsOff + i * 32);
            if (classIdx < 0 || classIdx >= typeIdsSize
                    || !inRange(dex, typeIdsOff, (classIdx + 1) * 4)) continue;
            int descIdx = u32(dex, typeIdsOff + classIdx * 4);
            if (descIdx < 0 || descIdx >= strings.length) continue;
            String desc = strings[descIdx];
            if (desc != null && desc.length() > 2
                    && desc.charAt(0) == 'L' && desc.charAt(desc.length() - 1) == ';') {
                out.add(desc.substring(1, desc.length() - 1).replace('/', '.'));
            }
        }
    }

    private static void parseMethods(byte[] dex, String targetDesc, List<MethodInfo> out) {
        if (dex.length < 0x70) return;
        int stringIdsSize = u32(dex, 0x38);
        int stringIdsOff = u32(dex, 0x3C);
        int typeIdsSize = u32(dex, 0x40);
        int typeIdsOff = u32(dex, 0x44);
        int protoIdsSize = u32(dex, 0x48);
        int protoIdsOff = u32(dex, 0x4C);
        int methodIdsSize = u32(dex, 0x58);
        int methodIdsOff = u32(dex, 0x5C);
        if (methodIdsSize == 0 || !inRange(dex, methodIdsOff, methodIdsSize * 8)) return;
        String[] strings = readStrings(dex, stringIdsSize, stringIdsOff);
        if (strings == null) return;

        // cari type_idx dari descriptor target
        int targetTypeIdx = -1;
        if (inRange(dex, typeIdsOff, typeIdsSize * 4)) {
            for (int t = 0; t < typeIdsSize; t++) {
                int di = u32(dex, typeIdsOff + t * 4);
                if (di >= 0 && di < strings.length && targetDesc.equals(strings[di])) {
                    targetTypeIdx = t;
                    break;
                }
            }
        }
        if (targetTypeIdx < 0) return;

        for (int m = 0; m < methodIdsSize; m++) {
            int base = methodIdsOff + m * 8;
            int classIdx = u16(dex, base);
            if (classIdx != targetTypeIdx) continue;
            int protoIdx = u16(dex, base + 2);
            int nameIdx = u32(dex, base + 4);
            String name = (nameIdx >= 0 && nameIdx < strings.length) ? strings[nameIdx] : "?";
            String sig = buildSignature(dex, strings, typeIdsOff, typeIdsSize,
                    protoIdsOff, protoIdsSize, protoIdx);
            out.add(new MethodInfo(name, name + sig, m));
        }
    }

    private static String buildSignature(byte[] dex, String[] strings,
                                         int typeIdsOff, int typeIdsSize,
                                         int protoIdsOff, int protoIdsSize, int protoIdx) {
        try {
            if (protoIdx < 0 || protoIdx >= protoIdsSize
                    || !inRange(dex, protoIdsOff, (protoIdx + 1) * 12)) return "(?)";
            int base = protoIdsOff + protoIdx * 12;
            int retTypeIdx = u32(dex, base + 4);
            int paramsOff = u32(dex, base + 8);
            StringBuilder sb = new StringBuilder("(");
            if (paramsOff != 0 && inRange(dex, paramsOff, 4)) {
                int count = u32(dex, paramsOff);
                for (int i = 0; i < count && i < 64; i++) {
                    if (!inRange(dex, paramsOff + 4 + i * 4, 4)) break;
                    int ti = u32(dex, paramsOff + 4 + i * 4);
                    if (i > 0) sb.append(", ");
                    sb.append(readableType(typeDesc(dex, strings, typeIdsOff, typeIdsSize, ti)));
                }
            }
            sb.append("): ").append(
                    readableType(typeDesc(dex, strings, typeIdsOff, typeIdsSize, retTypeIdx)));
            return sb.toString();
        } catch (Exception e) {
            return "(?)";
        }
    }

    private static String typeDesc(byte[] dex, String[] strings,
                                   int typeIdsOff, int typeIdsSize, int typeIdx) {
        if (typeIdx < 0 || typeIdx >= typeIdsSize
                || !inRange(dex, typeIdsOff, (typeIdx + 1) * 4)) return "?";
        int di = u32(dex, typeIdsOff + typeIdx * 4);
        if (di < 0 || di >= strings.length || strings[di] == null) return "?";
        return strings[di];
    }

    /** Ubah descriptor JVM (Ljava/lang/String;, [I, ...) jadi readable. */
    private static String readableType(String desc) {
        int dims = 0;
        while (desc.startsWith("[")) {
            dims++;
            desc = desc.substring(1);
        }
        String base;
        switch (desc) {
            case "V": base = "void"; break;
            case "Z": base = "boolean"; break;
            case "B": base = "byte"; break;
            case "S": base = "short"; break;
            case "C": base = "char"; break;
            case "I": base = "int"; break;
            case "J": base = "long"; break;
            case "F": base = "float"; break;
            case "D": base = "double"; break;
            default:
                if (desc.length() > 2 && desc.charAt(0) == 'L'
                        && desc.charAt(desc.length() - 1) == ';') {
                    base = desc.substring(1, desc.length() - 1).replace('/', '.');
                } else {
                    base = desc;
                }
        }
        StringBuilder sb = new StringBuilder(base);
        for (int i = 0; i < dims; i++) sb.append("[]");
        return sb.toString();
    }

    private static String[] readStrings(byte[] dex, int size, int off) {
        if (size <= 0 || size > 500000 || !inRange(dex, off, size * 4)) return null;
        String[] out = new String[size];
        for (int i = 0; i < size; i++) {
            int strOff = u32(dex, off + i * 4);
            out[i] = readStringData(dex, strOff);
        }
        return out;
    }

    private static String readStringData(byte[] dex, int off) {
        try {
            if (off < 0 || off >= dex.length) return null;
            int[] r = readUleb128(dex, off);
            int pos = r[1];
            // cari terminator 0x00 (MUTF-8, 0xC0 0x80 = NUL yang di-encode)
            int end = pos;
            while (end < dex.length && dex[end] != 0) end++;
            return decodeMutf8(dex, pos, end - pos);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decoder MUTF-8 (Java modified UTF-8). */
    private static String decodeMutf8(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        int end = off + len;
        int i = off;
        while (i < end) {
            int c = b[i++] & 0xff;
            if (c == 0) break;
            if (c < 0x80) {
                sb.append((char) c);
            } else if ((c & 0xe0) == 0xc0 && i < end) {
                int c2 = b[i++] & 0xff;
                sb.append((char) (((c & 0x1f) << 6) | (c2 & 0x3f)));
            } else if ((c & 0xf0) == 0xe0 && i + 1 < end) {
                int c2 = b[i++] & 0xff;
                int c3 = b[i++] & 0xff;
                sb.append((char) (((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f)));
            } else {
                // sekuens 4-byte / CESU-8 surrogate pair: lewati best-effort
                int skip = (c & 0xf8) == 0xf0 ? 3 : 1;
                i += Math.min(skip, end - i);
                sb.append('?');
            }
        }
        return sb.toString();
    }

    // ---------- util biner ----------

    private static boolean inRange(byte[] b, int off, int len) {
        return off >= 0 && len >= 0 && off + len <= b.length && off + len >= off;
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    /** @return {value, offsetBaru} */
    private static int[] readUleb128(byte[] b, int off) {
        int v = 0, shift = 0, i = off;
        while (i < b.length) {
            int c = b[i++] & 0xff;
            v |= (c & 0x7f) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) break;
        }
        return new int[]{v, i};
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
