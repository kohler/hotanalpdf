package org.lcdf.hotanalpdf;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.property.TextAlignment;
import com.itextpdf.layout.property.VerticalAlignment;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.utils.PdfMerger;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfNumber;
import com.itextpdf.kernel.pdf.PdfObject;
import com.itextpdf.kernel.pdf.PdfIndirectReference;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.PdfString;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.geom.PageSize;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;

import org.apache.commons.io.IOUtils;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.HelpFormatter;


public class App {
    private PdfDocument thepdf = null;
    private AppArgs appArgs = null;

    private JsonArrayBuilder fmtErrors = Json.createArrayBuilder();
    private TreeSet<String> fmtErrorsGiven = new TreeSet<String>();
    private int errorTypes = 0;
    private TreeSet<String> viewedFontRefs = new TreeSet<String>();
    private TreeSet<String> type3FontNames = new TreeSet<String>();
    private TreeSet<String> nonEmbeddedFontNames = new TreeSet<String>();
    private boolean documentModified = false;

    static public class EmbedFontInfo {
        public String baseName;
        public String fontName;
        public String filePrefix;
        public boolean symbolic;
        private byte[] pfbData = null;
        private byte[] afmData = null;
        private FontProgram fontProgram = null;
        private boolean loaded = false;
        public PdfIndirectReference descriptorReference = null;
        static private String kpsewhich = "kpsewhich";
        static private boolean kpsewhichChecked = false;

        public EmbedFontInfo(String baseName, String fontName, String filePrefix, boolean symbolic) {
            this.baseName = baseName;
            this.fontName = fontName;
            this.filePrefix = filePrefix;
            this.symbolic = symbolic;
        }
        public boolean load() {
            if (!loaded) {
                loaded = true;
                pfbData = getContents(filePrefix, ".pfb");
                afmData = getContents(filePrefix, ".afm");
            }
            return pfbData != null && afmData != null;
        }
        public FontProgram getFontProgram() throws IOException {
            if (fontProgram == null && load()) {
                fontProgram = FontProgramFactory.createType1Font(afmData, pfbData);
            }
            return fontProgram;
        }
        public PdfFont getBaseFont() throws IOException {
            return PdfFontFactory.createFont(getFontProgram(), PdfEncodings.WINANSI, true);
        }
        public PdfFont getBaseFont(PdfDictionary fontDict) {
            return getBaseFont(AnalEncoding.getPdfEncoding(fontDict));
        }
        public PdfFont getBaseFont(AnalEncoding encoding) {
            try {
                return PdfFontFactory.createFont(getFontProgram(), encoding.unparseItext(), true);
            } catch (Throwable e) {
                return null;
            }
        }
        public PdfDictionary getFontDescriptor(PdfFont font) throws IOException {
            Class<?>[] classArray = {PdfIndirectReference.class};
            try {
                java.lang.reflect.Method method = font.getClass().getDeclaredMethod("getFontDescriptor", classArray);
                method.setAccessible(true);
                Object[] args = {fontName};
                PdfDictionary descriptor = (PdfDictionary) method.invoke(font, args);
                String theirName = descriptor.getAsName(PdfName.FontName).getValue();
                if (!theirName.equals(fontName)) {
                    System.err.println(baseName + " replacement: expected " + fontName + ", got " + theirName);
                    fontName = theirName;
                }
                return descriptor;
            } catch (Throwable t) {
                throw new NullPointerException();
            }
        }
        static private byte[] getContents(String prefix, String suffix) {
            try {
                String f = findFile(prefix, suffix);
                if (f != null) {
                    java.io.InputStream fileStream = new java.io.FileInputStream(f);
                    return IOUtils.toByteArray(fileStream);
                }
            } catch (IOException e) {
            }
            return null;
        }
        static private String findFile(String prefix, String suffix) {
            if (new java.io.File(prefix + suffix).exists())
                return prefix + suffix;
            String error = "";
            try {
                String[] args = {kpsewhich, prefix + suffix};
                Process p = Runtime.getRuntime().exec(args);
                String result = IOUtils.toString(p.getInputStream(), "UTF-8").trim();
                error = IOUtils.toString(p.getErrorStream(), "UTF-8").trim();
                p.destroy();
                if (error != "" && error.indexOf("remove_dots") >= 0 && !kpsewhichChecked)
                    throw new IOException();
                if (result != "" && new java.io.File(result).exists())
                    return result;
            } catch (IOException e) {
                if (!kpsewhichChecked) {
                    kpsewhichChecked = true;
                    if (new java.io.File("/usr/bin/kpsewhich").exists())
                        kpsewhich = "/usr/bin/kpsewhich";
                    else if (new java.io.File("/usr/local/bin/kpsewhich").exists())
                        kpsewhich = "/usr/local/bin/kpsewhich";
                    else if (new java.io.File("/opt/local/bin/kpsewhich").exists())
                        kpsewhich = "/opt/local/bin/kpsewhich";
                    if (!kpsewhich.equals("kpsewhich"))
                        return findFile(prefix, suffix);
                }
                System.err.println(e.toString());
            }
            int i = prefix.lastIndexOf('-');
            if (i >= 0)
                return findFile(prefix.substring(0, i), suffix);
            if (error != "")
                System.err.println(error);
            return null;
        }
    }

    static public class AnalEncoding {
        public String[] encoding = new String[256];

        static private TreeMap<String, AnalEncoding> defaultEncodings = new TreeMap<String, AnalEncoding>();
        static private TreeMap<String, Integer> unicodeMap = null;

        public AnalEncoding copy() {
            AnalEncoding c = new AnalEncoding();
            for (int i = 0; i < 256; ++i)
                c.encoding[i] = this.encoding[i];
            return c;
        }
        public String unparseItext() {
            makeUnicodeMap();
            StringBuffer buffer = new StringBuffer(1024);
            buffer.append("# full");
            for (int i = 0; i < 256; ++i)
                if (encoding[i] != null && !encoding[i].equals(".notdef")) {
                    buffer.append(" ");
                    buffer.append(i);
                    buffer.append(" ");
                    buffer.append(encoding[i]);
                    buffer.append(" ");
                    buffer.append(String.format("%04X", unicodeMap.get(encoding[i])));
                }
            return buffer.toString();
        }

        static public int unicodeFor(String name) {
            makeUnicodeMap();
            Integer i = unicodeMap.get(name);
            return i == null ? -1 : i.intValue();
        }
        static private void makeUnicodeMap() {
            if (unicodeMap == null) {
                unicodeMap = new TreeMap<String, Integer>();
                try {
                    java.io.InputStream stream = App.class.getResourceAsStream("/UnicodeNames.txt");
                    BufferedReader br = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                    String line = null;
                    while ((line = br.readLine()) != null) {
                        String[] words = line.split("\\s+");
                        if (words.length >= 2)
                            unicodeMap.put(words[0], Integer.parseInt(words[1], 16));
                    }
                    stream.close();
                } catch (Throwable e) {
                }
            }
        }

        static public AnalEncoding getEncoding(String name) {
            if (!defaultEncodings.containsKey(name)) {
                AnalEncoding encoding = new AnalEncoding();
                try {
                    java.io.InputStream stream = App.class.getResourceAsStream("/" + name + ".txt");
                    BufferedReader br = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                    String line = null;
                    while ((line = br.readLine()) != null) {
                        String[] words = line.split("\\s+");
                        if (words.length >= 3)
                            encoding.encoding[Integer.parseInt(words[0], 10)] = words[1];
                    }
                    stream.close();
                } catch (Throwable e) {
                }
                defaultEncodings.put(name, encoding);
            }
            return defaultEncodings.get(name);
        }
        static private AnalEncoding getBasePdfEncoding(PdfDictionary font) {
            String fontNameStr = font.getAsName(PdfName.BaseFont).toString();
            if (fontNameStr == "/Symbol")
                return getEncoding("SymbolEncoding");
            else if (fontNameStr == "/ZapfDingbats")
                return getEncoding("ZapfDingbatsEncoding");
            else
                return getEncoding("StandardEncoding");
        }
        static public AnalEncoding getPdfEncoding(PdfDictionary font) {
            PdfObject encodingObj = font.get(PdfName.Encoding);
            if (encodingObj != null && encodingObj.isName())
                return getEncoding(((PdfName) encodingObj).toString().substring(1));
            else if (encodingObj != null && (encodingObj.isDictionary() || encodingObj.isIndirectReference())) {
                PdfDictionary encodingDict = font.getAsDictionary(PdfName.Encoding);
                PdfName base = encodingDict.getAsName(PdfName.BaseEncoding);
                AnalEncoding e;
                if (base != null)
                    e = getEncoding(base.toString().substring(1)).copy();
                else
                    e = getBasePdfEncoding(font).copy();
                PdfArray differences = encodingDict.getAsArray(PdfName.Differences);
                if (differences != null) {
                    int nextCode = -1;
                    for (int i = 0; i < differences.size(); ++i)
                        if (differences.get(i).isNumber())
                            nextCode = differences.getAsNumber(i).intValue();
                        else if (nextCode >= 0 && nextCode < 256) {
                            e.encoding[nextCode] = differences.getAsName(i).getValue();
                            ++nextCode;
                        }
                }
                return e;
            } else
                return getBasePdfEncoding(font);
        }
    }

    private TreeMap<String, EmbedFontInfo> embedFontMap = null;
    private TreeMap<String, PdfIndirectReference> loadedFonts = new TreeMap<String, PdfIndirectReference>();

    public final int ERR_FONT_TYPE3 = 1;
    public final int ERR_FONT_NOTEMBEDDED = 2;
    public final int ERR_JAVASCRIPT = 4;
    public final int ERR_ANONYMITY = 8;
    public final String errfNameMap[] = {null, "fonttype", "fontembed", null, "javascript", null, null, null, "authormeta"};

    static public class AppArgs {
        public boolean paginate = false;
        public int firstPage = 0;
        public int skipPagination = -1;
        public int footerSize = 9;
        public String lfoot = "";
        public String cfoot = "";
        public String rfoot = "";
        public String lefoot = null;
        public String refoot = null;
        public String lofoot = null;
        public String rofoot = null;
        public boolean twoSided = false;
        public PdfFont footerFont = null;
        public float lmargin = 54;
        public float rmargin = 54;
        public float bmargin = 28;
        public float footerRulePosition = 0;
        public float footerRuleWidth = 0;
        public Vector<String> inputFiles = new Vector<String>();
        public int paginateFilePosition = -1;
        public int blankPages = 0;
        public boolean outputFileGiven = false;
        public String outputFile = "-";
        public int unmodifiedStatus = 0;
        public boolean jsonOutput = false;
        public boolean checkFonts = false;
        public boolean embedFonts = false;
        public boolean checkJS = false;
        public boolean checkAnonymity = false;
        public boolean strip = false;
    }

    public static void main(String[] args) throws IOException, NumberFormatException, ParseException {
        new App().runMain(args);
    }

    private void addError(int errorType, String error) {
        errorTypes |= errorType;
        if (!fmtErrorsGiven.contains(error)) {
            fmtErrorsGiven.add(error);
            if (appArgs.jsonOutput)
                fmtErrors.add(Json.createArrayBuilder().add(errfNameMap[errorType]).add(error).build());
            else
                System.err.println(error);
        }
    }

    public AppArgs parseArgs(String[] args) {
        Options options = new Options();
        options.addOption(Option.builder("o").longOpt("output").desc("write output PDF to FILE")
                          .hasArg(true).argName("FILE").build());
        options.addOption(Option.builder().longOpt("unmodified-status").desc("exit status if input unmodified").hasArg(true).argName("STATUS").build());
        options.addOption(Option.builder("j").longOpt("json").desc("write JSON output to stdout").build());
        options.addOption(Option.builder("F").longOpt("check-fonts").desc("check font embedding").build());
        options.addOption(Option.builder("J").longOpt("check-javascript").desc("check for JavaScript actions").build());
        options.addOption(Option.builder("A").longOpt("check-anonymous").desc("check metadata for anonymity").build());
        options.addOption(Option.builder("s").longOpt("strip").desc("strip JS/metadata").build());
        options.addOption(Option.builder().longOpt("embed-fonts").desc("embed missing fonts when possible").build());
        options.addOption(Option.builder("p").longOpt("paginate").desc("paginate starting at PAGENO")
                          .hasArg(true).argName("PAGENO").build());
        options.addOption(Option.builder().longOpt("roman").desc("paginate in lowercase Roman numerals").build());
        options.addOption(Option.builder().longOpt("lfoot").desc("left-hand footer").hasArg(true).build());
        options.addOption(Option.builder().longOpt("cfoot").desc("center footer").hasArg(true).build());
        options.addOption(Option.builder().longOpt("rfoot").desc("right-hand footer").hasArg(true).build());
        options.addOption(Option.builder().longOpt("lefoot").desc("left-hand footer, even-numbered pages").hasArg(true).build());
        options.addOption(Option.builder().longOpt("lofoot").desc("left-hand footer, odd-numbered pages").hasArg(true).build());
        options.addOption(Option.builder().longOpt("refoot").desc("right-hand footer, even-numbered pages").hasArg(true).build());
        options.addOption(Option.builder().longOpt("rofoot").desc("right-hand footer, odd-numbered pages").hasArg(true).build());
        options.addOption(Option.builder().longOpt("lmargin").desc("left margin [54]").hasArg(true).build());
        options.addOption(Option.builder().longOpt("rmargin").desc("right margin [54]").hasArg(true).build());
        options.addOption(Option.builder().longOpt("bmargin").desc("bottom margin [28]").hasArg(true).build());
        options.addOption(Option.builder().longOpt("two-sided").desc("swap left/right foot/margin on even pages").build());
        options.addOption(Option.builder().longOpt("footer-font").desc("footer font file [Times Roman]")
                          .hasArg(true).argName("").build());
        options.addOption(Option.builder().longOpt("footer-size").desc("footer size [9]")
                          .hasArg(true).argName("").build());
        options.addOption(Option.builder().longOpt("footer-rule").desc("footer rule POSITION[,WIDTH] in points")
                          .hasArg(true).argName("").build());
        options.addOption(Option.builder().longOpt("skip-pagination").desc("don't paginate first N pages")
                          .hasArg(true).argName("N").build());
        options.addOption(Option.builder().longOpt("add-blank").desc("add N blank pages at end")
                          .hasArg(true).argName("N").build());
        options.addOption(Option.builder().longOpt("help").desc("print this message").build());
        int status = 0;

        try {
            CommandLine cl = new DefaultParser().parse(options, args);
            AppArgs appArgs = new AppArgs();

            for (int i = 0; i < cl.getArgs().length; ++i) {
                if (cl.getArgs()[i].equals("@"))
                    appArgs.paginateFilePosition = appArgs.inputFiles.size();
                else
                    appArgs.inputFiles.addElement(cl.getArgs()[i]);
            }
            if (appArgs.paginateFilePosition < 0)
                appArgs.paginateFilePosition = 0;
            if (appArgs.inputFiles.size() == 0)
                appArgs.inputFiles.addElement("-");
            if (cl.hasOption('o')) {
                appArgs.outputFileGiven = true;
                appArgs.outputFile = cl.getOptionValue('o');
            }
            if (cl.hasOption("unmodified-status"))
                appArgs.unmodifiedStatus = Integer.parseInt(cl.getOptionValue("unmodified-status"));
            if (cl.hasOption('j'))
                appArgs.jsonOutput = true;

            if (cl.hasOption("lfoot"))
                appArgs.lfoot = cl.getOptionValue("lfoot");
            if (cl.hasOption("cfoot"))
                appArgs.cfoot = cl.getOptionValue("cfoot");
            if (cl.hasOption("rfoot"))
                appArgs.rfoot = cl.getOptionValue("rfoot");
            if (cl.hasOption("lefoot"))
                appArgs.lefoot = cl.getOptionValue("lefoot");
            if (cl.hasOption("lofoot"))
                appArgs.lofoot = cl.getOptionValue("lofoot");
            if (cl.hasOption("refoot"))
                appArgs.refoot = cl.getOptionValue("refoot");
            if (cl.hasOption("rofoot"))
                appArgs.rofoot = cl.getOptionValue("rofoot");
            if (appArgs.lfoot != "" || appArgs.cfoot != "" || appArgs.rfoot != ""
                || (appArgs.lefoot != null && appArgs.lefoot != "")
                || (appArgs.lofoot != null && appArgs.lofoot != "")
                || (appArgs.refoot != null && appArgs.refoot != "")
                || (appArgs.rofoot != null && appArgs.rofoot != "")) {
                appArgs.paginate = true;
            } else if (cl.hasOption('p')) {
                appArgs.cfoot = cl.hasOption("roman") ? "%r" : "%d";
                appArgs.paginate = true;
            }
            if (appArgs.paginateFilePosition >= appArgs.inputFiles.size()) {
                appArgs.paginateFilePosition = 0;
                appArgs.paginate = false;
            }
            if (cl.hasOption('p'))
                appArgs.firstPage = Integer.parseInt(cl.getOptionValue('p'));
            if (cl.hasOption("footer-font"))
                setFooterFont(appArgs, cl.getOptionValue("footer-font"));
            if (cl.hasOption("footer-size"))
                appArgs.footerSize = Integer.parseInt(cl.getOptionValue("footer-size"));
            if (cl.hasOption("footer-rule")) {
                String s = cl.getOptionValue("footer-rule");
                int comma = s.indexOf(',');
                if (comma >= 0) {
                    appArgs.footerRulePosition = Float.parseFloat(s.substring(0, comma));
                    appArgs.footerRuleWidth = Float.parseFloat(s.substring(comma + 1));
                } else {
                    appArgs.footerRulePosition = Float.parseFloat(s);
                    appArgs.footerRuleWidth = 1;
                }
                if (appArgs.footerRuleWidth > 0)
                    appArgs.paginate = true;
            }
            if (cl.hasOption("skip-pagination"))
                appArgs.skipPagination = Integer.parseInt(cl.getOptionValue("skip-pagination"));

            if (cl.hasOption("lmargin"))
                appArgs.lmargin = Float.parseFloat(cl.getOptionValue("lmargin"));
            if (cl.hasOption("rmargin"))
                appArgs.rmargin = Float.parseFloat(cl.getOptionValue("rmargin"));
            if (cl.hasOption("bmargin"))
                appArgs.bmargin = Float.parseFloat(cl.getOptionValue("bmargin"));
            if (cl.hasOption("two-sided"))
                appArgs.twoSided = true;

            if (cl.hasOption("add-blank")) {
                appArgs.blankPages = Integer.parseInt(cl.getOptionValue("add-blank"));
                if (appArgs.blankPages < 0)
                    throw new NumberFormatException();
            }

            if (cl.hasOption('F'))
                appArgs.checkFonts = true;
            if (cl.hasOption("embed-fonts"))
                appArgs.embedFonts = true;
            if (cl.hasOption('J'))
                appArgs.checkJS = true;
            if (cl.hasOption('A'))
                appArgs.checkAnonymity = true;
            if (cl.hasOption('s'))
                appArgs.strip = true;

            if (appArgs.strip && !appArgs.checkJS && !appArgs.checkAnonymity) {
                System.err.println("`--strip` requires `--js` or `--anonymity`");
                throw new NumberFormatException();
            }
            if (appArgs.strip && appArgs.jsonOutput && appArgs.outputFile.equals("-")) {
                System.err.println("`--json` plus `--strip` requires output file");
                throw new NumberFormatException();
            }
            if (!cl.hasOption("help"))
                return appArgs;
        } catch (Throwable e) {
            status = 1;
        }

        HelpFormatter formatter = new HelpFormatter();
        java.io.PrintWriter pw = new java.io.PrintWriter(status == 1 ? System.err : System.out);
        formatter.printHelp(pw, 80, "hotanalpdf", "Check and/or paginate PDFs for HotCRP.", options, 0, 0, "\nPlease report issues at https://github.com/kohler/hotanalpdf", true);
        pw.flush();
        System.exit(status);
        return null;
    }
    private void setFooterFont(AppArgs appArgs, String fileName) throws IOException {
        if (fileName.indexOf('.') < 0) {
            EmbedFontInfo embedFont = lookupEmbedFont(fileName);
            if (embedFont != null) {
                try {
                    appArgs.footerFont = embedFont.getBaseFont();
                    return;
                } catch (Throwable e) {
                    System.err.println(fileName + ": cannot load, " + e.toString());
                    throw e;
                }
            }
        }
        try {
            int comma = fileName.indexOf(',');
            PdfFont font = null;
            if (comma >= 0) {
                String fileName1 = fileName.substring(0, comma);
                String fileName2 = fileName.substring(comma + 1);
                if (fileName2.toLowerCase().endsWith(".pfb")) {
                    String tmp = fileName1;
                    fileName1 = fileName2;
                    fileName2 = tmp;
                }
                if (fileName1.toLowerCase().endsWith(".pfb")
                    && fileName2.toLowerCase().endsWith(".afm")) {
                    java.io.InputStream pfbStream = new java.io.FileInputStream(fileName1);
                    java.io.InputStream afmStream = new java.io.FileInputStream(fileName2);
                    byte[] pfbData = IOUtils.toByteArray(pfbStream);
                    byte[] afmData = IOUtils.toByteArray(afmStream);
                    FontProgram fontProgram = FontProgramFactory.createType1Font(afmData, pfbData);
                    font = PdfFontFactory.createFont(fontProgram, PdfEncodings.WINANSI, true);
                }
            }
            if (font == null) {
                java.io.InputStream fileStream = new java.io.FileInputStream(fileName);
                byte[] fileData = IOUtils.toByteArray(fileStream);
                font = PdfFontFactory.createFont(fileData, PdfEncodings.IDENTITY_H, true);
            }
            appArgs.footerFont = font;
        } catch (IOException e) {
            System.err.println(e.getMessage());
            throw e;
        }
    }

    private PageSize calculateDefaultPageSize(PdfDocument doc) {
        TreeMap<PageSize, Integer> pageSizeMap = new TreeMap<PageSize, Integer>(
            new java.util.Comparator<PageSize>() {
                @Override
                public int compare(PageSize e1, PageSize e2) {
                    if (e1.getWidth() < e2.getWidth()
                        || (e1.getWidth() == e2.getWidth() && e1.getHeight() < e2.getHeight()))
                        return -1;
                    else if (e1.getWidth() == e2.getWidth() && e1.getHeight() == e2.getHeight())
                        return 0;
                    else
                        return 1;
                }
            }
        );
        for (int p = 1; p <= doc.getNumberOfPages(); ++p) {
            PageSize thisPageSize = new PageSize(doc.getPage(p).getPageSize());
            Integer n = pageSizeMap.get(thisPageSize);
            if (n == null)
                pageSizeMap.put(thisPageSize, 1);
            else
                pageSizeMap.put(thisPageSize, n + 1);
        }
        if (pageSizeMap.isEmpty()
            || pageSizeMap.containsKey(doc.getDefaultPageSize()))
            return doc.getDefaultPageSize();
        PageSize pageSize = null;
        int count = 0;
        for (Map.Entry<PageSize, Integer> entry : pageSizeMap.entrySet()) {
            if (entry.getValue() > count) {
                pageSize = entry.getKey();
                count = entry.getValue();
            }
        }
        return pageSize;
    }

    private PdfReader getInputFileReader(int filePos) throws IOException {
        if (appArgs.inputFiles.get(filePos).equals("-"))
            return new PdfReader(System.in);
        else
            return new PdfReader(appArgs.inputFiles.get(filePos));
    }

    public void runMain(String[] args) throws IOException, NumberFormatException {
        appArgs = parseArgs(args);

        boolean maybeModified = appArgs.paginate || appArgs.strip || appArgs.embedFonts;

        // read and merge files
        PdfReader reader;
        if (appArgs.inputFiles.size() > 1 || appArgs.blankPages > 0) {
            ByteArrayOutputStream mergedOutputStream = new ByteArrayOutputStream();
            PdfDocument mergedDocument = new PdfDocument(new PdfWriter(mergedOutputStream));
            PdfMerger mergedCopy = new PdfMerger(mergedDocument);

            int pageCount = 0;
            for (int filePos = 0; filePos < appArgs.inputFiles.size(); ++filePos) {
                PdfDocument doc = new PdfDocument(getInputFileReader(filePos));
                pageCount += doc.getNumberOfPages();
                if (filePos + 1 == appArgs.paginateFilePosition
                    && appArgs.skipPagination < 0)
                    appArgs.skipPagination = pageCount;
                if (filePos == 0)
                    mergedDocument.setDefaultPageSize(calculateDefaultPageSize(doc));
                mergedCopy.merge(doc, 1, doc.getNumberOfPages());
                doc.close();
            }

            for (int i = 0; i < appArgs.blankPages; ++i)
                mergedDocument.addNewPage(mergedDocument.getDefaultPageSize());

            mergedDocument.close();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(mergedOutputStream.toByteArray());
            mergedOutputStream.close();
            reader = new PdfReader(inputStream);
            documentModified = maybeModified = true;
        } else {
            reader = getInputFileReader(0);
        }

        {
            java.io.OutputStream output;
            if (appArgs.outputFile.equals("-"))
                output = System.out;
            else
                output = new FileOutputStream(appArgs.outputFile);
            thepdf = new PdfDocument(reader, new PdfWriter(output));
        }

        // actions
        if (appArgs.checkFonts || appArgs.embedFonts)
            checkFonts();
        if (appArgs.checkJS)
            checkJavascripts(appArgs.strip);
        if (appArgs.checkAnonymity)
            checkAnonymity(appArgs.strip);
        if (appArgs.paginate)
            paginate();

        // output
        int numPages = thepdf.getNumberOfPages();
        thepdf.close();

        if (appArgs.jsonOutput) {
            JsonObjectBuilder result = Json.createObjectBuilder()
                .add("ok", true)
                .add("at", (long) (System.currentTimeMillis() / 1000L))
                .add("npages", numPages);
            if (maybeModified)
                result.add("modified", documentModified);
            JsonArrayBuilder errfResult = Json.createArrayBuilder();
            for (int x = 0; x < 4; ++x)
                if ((errorTypes & (1 << x)) != 0)
                    errfResult.add(errfNameMap[1 << x]);
            if (errorTypes != 0) {
                result.add("errf", errfResult.build());
                result.add("fmt_errors", fmtErrors.build());
            }
            StringWriter stWriter = new StringWriter();
            try (JsonWriter jsonWriter = Json.createWriter(stWriter)) {
                jsonWriter.write(result.build());
            }
            PrintStream out = new PrintStream(System.out, true, "UTF-8");
            out.println(stWriter.toString());
        }

        if (errorTypes != 0)
            System.exit(1);
        else if (!documentModified)
            System.exit(appArgs.unmodifiedStatus);
        else
            System.exit(0);
    }

    static final private String romanNumeralOut[] = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
    static final private int romanNumeralIn[] = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    static public void romanNumerals(StringBuilder sb, int n) {
        int pos = 0;
        while (n > 0)
            if (n >= romanNumeralIn[pos]) {
                sb.append(romanNumeralOut[pos]);
                n -= romanNumeralIn[pos];
            } else
                ++pos;
    }
    static public String expandFooter(String format, int pageno) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < format.length()) {
            int pct = format.indexOf('%', pos);
            if (pct < 0)
                pct = format.length();
            sb.append(format, pos, pct);
            if (pct + 1 < format.length() && format.charAt(pct + 1) == 'd') {
                sb.append(pageno);
                pct += 2;
            } else if (pct + 1 < format.length() && format.charAt(pct + 1) == 'r') {
                romanNumerals(sb, pageno);
                pct += 2;
            } else if (pct + 1 < format.length() && format.charAt(pct + 1) == '%') {
                sb.append('%');
                pct += 2;
            } else if (pct < format.length()) {
                sb.append('%');
                pct += 1;
            }
            pos = pct;
        }
        return sb.toString();
    }
    static public boolean complexFooterString(String format) {
        if (format == null)
            return false;
        int pos = 0;
        while (pos < format.length()) {
            char ch = format.charAt(pos);
            if ((ch >= '0' && ch <= '9') || ch == ' ' || ch == ',' || ch == '.'
                || ch == '-' || ch == '/' || ch == ':' || ch == 'c' || ch == 'i'
                || ch == 'l' || ch == 'v' || ch == 'x')
                ++pos;
            else if (ch == '%' && pos + 1 < format.length()
                     && (format.charAt(pos + 1) == 'r' || format.charAt(pos + 1) == 'd'))
                pos += 2;
            else
                return true;
        }
        return false;
    }
    public boolean complexFooter() {
        return complexFooterString(appArgs.lfoot) || complexFooterString(appArgs.cfoot)
            || complexFooterString(appArgs.rfoot) || complexFooterString(appArgs.lefoot)
            || complexFooterString(appArgs.lofoot) || complexFooterString(appArgs.refoot)
            || complexFooterString(appArgs.rofoot);
    }
    private Paragraph footerParagraph(String text) {
        return new Paragraph(text).setFont(appArgs.footerFont).setFontSize(appArgs.footerSize);
    }
    public void paginate() throws IOException {
        if (appArgs.footerFont == null && !complexFooter()) {
            java.io.InputStream numberFontStream = this.getClass().getResourceAsStream("/HotCRPNumberTime.otf");
            byte[] numberFontBytes = IOUtils.toByteArray(numberFontStream);
            appArgs.footerFont = PdfFontFactory.createFont(numberFontBytes, PdfEncodings.WINANSI, true);
        } else if (appArgs.footerFont == null)
            appArgs.footerFont = lookupEmbedFont("Times-Roman").getBaseFont();
        int firstLocalPage = 1 + Math.max(appArgs.skipPagination, 0);

        for (int p = firstLocalPage; p <= thepdf.getNumberOfPages() - appArgs.blankPages; ++p) {
            int pageno = appArgs.firstPage + p - firstLocalPage;
            boolean flipped = appArgs.twoSided && pageno % 2 == 0;
            String lfoot = pageno % 2 == 0 ? appArgs.lefoot : appArgs.lofoot;
            if (lfoot == null)
                lfoot = flipped ? appArgs.rfoot : appArgs.lfoot;
            String rfoot = pageno % 2 == 0 ? appArgs.refoot : appArgs.rofoot;
            if (rfoot == null)
                rfoot = flipped ? appArgs.lfoot : appArgs.rfoot;
            Rectangle pagebox = thepdf.getPage(p).getPageSize();
            float lx = pagebox.getLeft() + (flipped ? appArgs.rmargin : appArgs.lmargin);
            float rx = pagebox.getRight() - (flipped ? appArgs.lmargin : appArgs.rmargin);
            float by = pagebox.getBottom() + appArgs.bmargin;
            PdfCanvas cb = new PdfCanvas(thepdf, p);
            if (lfoot != "") {
                Paragraph text = footerParagraph(expandFooter(lfoot, pageno));
                new Canvas(cb, thepdf, pagebox)
                    .showTextAligned(text, lx, by, p, TextAlignment.LEFT, VerticalAlignment.BOTTOM, 0);
            }
            if (appArgs.cfoot != "") {
                Paragraph text = footerParagraph(expandFooter(appArgs.cfoot, pageno));
                new Canvas(cb, thepdf, pagebox)
                    .showTextAligned(text, (lx + rx) / 2, by, p, TextAlignment.CENTER, VerticalAlignment.BOTTOM, 0);
            }
            if (rfoot != "") {
                Paragraph text = footerParagraph(expandFooter(rfoot, pageno));
                new Canvas(cb, thepdf, pagebox)
                    .showTextAligned(text, rx, by, p, TextAlignment.RIGHT, VerticalAlignment.BOTTOM, 0);
            }
            if (appArgs.footerRuleWidth > 0.0) {
                cb.saveState();
                cb.setFillColorRgb(0, 0, 0);
                cb.rectangle(lx, pagebox.getBottom() + appArgs.footerRulePosition, rx - lx, appArgs.footerRuleWidth);
                cb.fill();
                cb.restoreState();
            }
            documentModified = true;
        }
    }

    public void checkFonts() throws IOException {
        // parsing/traversing borrowed from `poppler/util/pdffonts`
        for (int p = 1; p <= thepdf.getNumberOfPages(); ++p) {
            PdfDictionary page = thepdf.getPage(p).getPdfObject();
            PdfDictionary res = page.getAsDictionary(PdfName.Resources);
            checkFontsDict(p, res);
            PdfArray annots = page.getAsArray(PdfName.Annots);
            if (annots != null)
                for (int j = 0; j < annots.size(); j++) {
                    PdfDictionary annot = annots.getAsDictionary(j);
                    checkFontsDict(j, annot.getAsDictionary(PdfName.Resources));
                }
        }
    }
    public void checkFontsDict(int p, PdfDictionary res) {
        if (res == null)
            return;
        checkFontsRefs(p, res, PdfName.XObject);
        checkFontsRefs(p, res, PdfName.Pattern);
        PdfDictionary fonts = res.getAsDictionary(PdfName.Font);
        if (fonts != null) {
            for (PdfName key : fonts.keySet())
                checkFont(p, fonts.get(key));
        }
    }
    static private String refName(PdfObject obj) {
        PdfIndirectReference ref = (PdfIndirectReference) obj;
        return ref.getObjNumber() + " " + ref.getGenNumber();
    }
    private boolean seenRef(PdfObject obj) {
        String refname = refName(obj);
        if (viewedFontRefs.contains(refname))
            return true;
        else {
            viewedFontRefs.add(refname);
            return false;
        }
    }
    public void checkFontsRefs(int p, PdfDictionary res, PdfName xkey) {
        PdfDictionary xres = res.getAsDictionary(xkey);
        if (xres == null)
            return;
        for (PdfName key : xres.keySet()) {
            PdfObject obj = xres.get(key);
            if (obj.isIndirectReference()) {
                if (seenRef(obj))
                    continue;
                else
                    obj = ((PdfIndirectReference) obj).getRefersTo(true);
            }
            if (obj.isStream()) {
                PdfDictionary strdict = (PdfDictionary) obj;
                PdfDictionary strres = strdict.getAsDictionary(PdfName.Resources);
                if (strres != null && strres != res)
                    checkFontsDict(p, strres);
            }
        }
    }
    public void checkFont(int p, PdfObject fontobj) {
        String refname = "[direct]";
        if (fontobj.isIndirectReference()) {
            if (seenRef(fontobj))
                return;
            refname = refName(fontobj);
            fontobj = ((PdfIndirectReference) fontobj).getRefersTo(true);
        }
        if (!fontobj.isDictionary())
            return;

        PdfDictionary font = (PdfDictionary) fontobj;
        PdfDictionary base = font;

        PdfName name = font.getAsName(PdfName.BaseFont);
        String namestr;
        if (name == null)
            namestr = "[no name]";
        else
            namestr = name.getValue();

        PdfName declared_type = font.getAsName(PdfName.Subtype);
        boolean isType0 = declared_type != null && declared_type.equals(PdfName.Type0);

        PdfName descendant_type = null;
        PdfArray descendants = font.getAsArray(PdfName.DescendantFonts);
        if (descendants != null && descendants.size() > 0) {
            PdfDictionary descendant0 = descendants.getAsDictionary(0);
            if (descendant0 != null) {
                base = descendant0;
                descendant_type = base.getAsName(PdfName.Subtype);
            }
        }

        PdfName embedded_type = null;
        PdfDictionary desc = base.getAsDictionary(PdfName.FontDescriptor);
        if (desc == null) {
            if (declared_type.equals(PdfName.Type3)
                && base.getAsDictionary(PdfName.CharProcs) != null)
                embedded_type = PdfName.Type3;
            else
                embedded_type = null;
        } else if (desc.get(PdfName.FontFile) != null)
            embedded_type = PdfName.Type1;
        else if (desc.get(PdfName.FontFile2) != null)
            embedded_type = isType0 ? PdfName.CIDFontType2 : PdfName.TrueType;
        else {
            PdfStream fontdict = desc.getAsStream(PdfName.FontFile3);
            if (fontdict != null)
                embedded_type = fontdict.getAsName(PdfName.Subtype);
            else
                embedded_type = null;
        }

        PdfName claimed_type = embedded_type;
        if (claimed_type == null)
            claimed_type = descendant_type;
        if (claimed_type == null)
            claimed_type = declared_type;

        if (claimed_type.equals(PdfName.Type3)) {
            if (!type3FontNames.contains(namestr)) {
                type3FontNames.add(namestr);
                if (namestr.equals("[no name]"))
                    addError(ERR_FONT_TYPE3, "Bad font: unnamed Type3 fonts first referenced on page " + p + ".");
                else
                    addError(ERR_FONT_TYPE3, "Bad font: Type3 font “" + namestr + "” first referenced on page " + p + ".");
            }
        } else if (embedded_type == null) {
            if (appArgs.embedFonts && tryEmbed(namestr, base))
                /* OK */;
            else if (!nonEmbeddedFontNames.contains(namestr)) {
                nonEmbeddedFontNames.add(namestr);
                addError(ERR_FONT_NOTEMBEDDED, "Missing font: “" + namestr + "” not embedded, first referenced on page " + p + ".");
            }
        }
    }

    private EmbedFontInfo lookupEmbedFont(String fontName) {
        if (embedFontMap == null)
            makeEmbedFontMap();
        EmbedFontInfo embedFont = embedFontMap.get(fontName);
        if (embedFont != null && embedFont.load())
            return embedFont;
        else
            return null;
    }
    private boolean tryEmbed(String fontName, PdfDictionary font) {
        EmbedFontInfo embedFont = lookupEmbedFont(fontName);
        if (embedFont == null)
            return false;

        AnalEncoding encoding = AnalEncoding.getPdfEncoding(font);
        PdfFont baseFont = embedFont.getBaseFont(encoding);
        if (baseFont == null)
            return false;

        if (embedFont.descriptorReference == null) {
            try {
                PdfDictionary newDescriptor = embedFont.getFontDescriptor(baseFont);
                embedFont.descriptorReference = newDescriptor.makeIndirect(thepdf).getIndirectReference();
            } catch (Throwable e) {
                return false;
            }
        }

        font.put(PdfName.BaseFont, new PdfName(embedFont.fontName));
        font.put(PdfName.FontDescriptor, embedFont.descriptorReference);
        if (!font.containsKey(PdfName.Widths)) {
            int firstChar = -1, lastChar = -1;
            PdfArray widths = new PdfArray();
            for (int ch = 0; ch < 256; ++ch)
                if (encoding.encoding[ch] != null && !encoding.encoding[ch].equals(".notdef")) {
                    if (firstChar < 0)
                        firstChar = ch;
                    else {
                        for (; lastChar + 1 < ch; ++lastChar)
                            widths.add(new PdfNumber(0));
                    }
                    lastChar = ch;
                    int w = baseFont.getWidth(AnalEncoding.unicodeFor(encoding.encoding[ch]));
                    widths.add(new PdfNumber(w));
                }
            try {
                font.put(PdfName.FirstChar, new PdfNumber(firstChar));
                font.put(PdfName.LastChar, new PdfNumber(lastChar));
                font.put(PdfName.Widths, widths.makeIndirect(thepdf).getIndirectReference());
            } catch (Throwable e) {
                return false;
            }
        }

        /*for (PdfName n : font.keySet())
            System.err.println(n.toString() + " " + font.get(n).toString());
        System.err.println(AnalEncoding.getPdfEncoding(font).unparseItext());*/

        documentModified = true;
        return true;
    }
    private void addEmbedFont(String baseName, String fontName, String filePrefix, boolean symbolic) {
        if (embedFontMap == null)
            embedFontMap = new TreeMap<String, EmbedFontInfo>();
        embedFontMap.put(baseName, new EmbedFontInfo(baseName, fontName, filePrefix, symbolic));
    }
    private void addEmbedFont(String baseName, String fontName, String filePrefix) {
        addEmbedFont(baseName, fontName, filePrefix, false);
    }
    private void makeEmbedFontMap() {
        addEmbedFont("Bookman-Demi", "URWBookmanL-DemiBold", "ubkd8a");
        addEmbedFont("Bookman-DemiItalic", "URWBookmanL-DemiBoldItal", "ubkdi8a");
        addEmbedFont("Bookman-Light", "URWBookmanL-Ligh", "ubkl8a");
        addEmbedFont("Bookman-LightItalic", "URWBookmanL-LighItal", "ubkli8a");
        addEmbedFont("Courier", "NimbusMonL-Regu", "ucrr8a");
        addEmbedFont("Courier-Oblique", "NimbusMonL-ReguObli", "ucrro8a");
        addEmbedFont("Courier-Bold", "NimbusMonL-Bold", "ucrb8a");
        addEmbedFont("Courier-BoldOblique", "NimbusMonL-BoldObli", "ucrbo8a");
        addEmbedFont("Courier New", "NimbusMonL-Regu", "ucrr8a");
        addEmbedFont("Courier New,Italic", "NimbusMonL-ReguObli", "ucrro8a");
        addEmbedFont("Courier New,Bold", "NimbusMonL-Bold", "ucrb8a");
        addEmbedFont("Courier New,BoldItalic", "NimbusMonL-BoldObli", "ucrbo8a");
        addEmbedFont("CourierNew", "NimbusMonL-Regu", "ucrr8a");
        addEmbedFont("CourierNew,Italic", "NimbusMonL-ReguObli", "ucrro8a");
        addEmbedFont("CourierNew,Bold", "NimbusMonL-Bold", "ucrb8a");
        addEmbedFont("CourierNew,BoldItalic", "NimbusMonL-BoldObli", "ucrbo8a");
        addEmbedFont("AvantGarde-Book", "URWGothicL-Book", "uagk8a");
        addEmbedFont("AvantGarde-BookOblique", "URWGothicL-BookObli", "uagko8a");
        addEmbedFont("AvantGarde-Demi", "URWGothicL-Demi", "uagd8a");
        addEmbedFont("AvantGarde-DemiOblique", "URWGothicL-DemiObli", "uagdo8a");
        addEmbedFont("Helvetica", "NimbusSanL-Regu", "uhvr8a-105");
        addEmbedFont("Helvetica-Oblique", "NimbusSanL-ReguItal", "uhvro8a-105");
        addEmbedFont("Helvetica-Bold", "NimbusSanL-Bold", "uhvb8a");
        addEmbedFont("Helvetica-BoldOblique", "NimbusSanL-BoldItal", "uhvbo8a");
        addEmbedFont("Helvetica-Narrow", "NimbusSanL-ReguCond", "uhvr8ac");
        addEmbedFont("Helvetica-Narrow-Oblique", "NimbusSanL-ReguCondItal", "uhvro8ac");
        addEmbedFont("Helvetica-Narrow-Bold", "NimbusSanL-BoldCond", "uhvb8ac");
        addEmbedFont("Helvetica-Narrow-BoldOblique", "NimbusSanL-BoldCondItal", "uhvbo8ac");
        addEmbedFont("Arial", "NimbusSanL-Regu", "uhvr8a-105");
        addEmbedFont("Arial,Italic", "NimbusSanL-ReguItal", "uhvro8a-105");
        addEmbedFont("Arial,Bold", "NimbusSanL-Bold", "uhvb8a");
        addEmbedFont("Arial,BoldItalic", "NimbusSanL-BoldItal", "uhvbo8a");
        addEmbedFont("Palatino-Roman", "URWPalladioL-Roma", "uplr8a");
        addEmbedFont("Palatino-Italic", "URWPalladioL-Ital", "uplri8a");
        addEmbedFont("Palatino-Bold", "URWPalladioL-Bold", "uplb8a");
        addEmbedFont("Palatino-BoldItalic", "URWPalladioL-BoldItal", "uplbi8a");
        addEmbedFont("NewCenturySchlbk-Roman", "CenturySchL-Roma", "uncr8a");
        addEmbedFont("NewCenturySchlbk-Italic", "CenturySchL-Ital", "uncri8a");
        addEmbedFont("NewCenturySchlbk-Bold", "CenturySchL-Bold", "uncb8a");
        addEmbedFont("NewCenturySchlbk-BoldItalic", "CenturySchL-BoldItal", "uncbi8a");
        addEmbedFont("Times-Roman", "NimbusRomNo9L-Regu", "utmr8a");
        addEmbedFont("Times-Italic", "NimbusRomNo9L-ReguItal", "utmri8a");
        addEmbedFont("Times-Bold", "NimbusRomNo9L-Medi", "utmb8a");
        addEmbedFont("Times-BoldItalic", "NimbusRomNo9L-MediItal", "utmbi8a");
        addEmbedFont("Times New Roman", "NimbusRomNo9L-Regu", "utmr8a");
        addEmbedFont("Times New Roman,Italic", "NimbusRomNo9L-ReguItal", "utmri8a");
        addEmbedFont("Times New Roman,Bold", "NimbusRomNo9L-Medi", "utmb8a");
        addEmbedFont("Times New Roman,BoldItalic", "NimbusRomNo9L-MediItal", "utmbi8a");
        addEmbedFont("TimesNewRoman", "NimbusRomNo9L-Regu", "utmr8a");
        addEmbedFont("TimesNewRoman,Italic", "NimbusRomNo9L-ReguItal", "utmri8a");
        addEmbedFont("TimesNewRoman,Bold", "NimbusRomNo9L-Medi", "utmb8a");
        addEmbedFont("TimesNewRoman,BoldItalic", "NimbusRomNo9L-MediItal", "utmbi8a");
        addEmbedFont("Symbol", "StandardSymL", "usyr", true);
        addEmbedFont("ZapfChancery-MediumItalic", "URWChanceryL-MediItal", "uzcmi8a");
        addEmbedFont("ZapfDingbats", "Dingbats", "uzdr", true);
    }

    public void checkJavascripts(boolean strip) throws IOException {
        PdfDictionary doccatalog = thepdf.getCatalog().getPdfObject();
        PdfDictionary aa = doccatalog.getAsDictionary(PdfName.AA);
        if (aa != null)
            checkJavascripts(aa, strip, " at document level");

        PdfDictionary names = doccatalog.getAsDictionary(PdfName.Names);
        if (names != null) {
            if (names.get(PdfName.JavaScript) != null)
                recordJavascript(names, PdfName.JavaScript, strip, " in global scripts");
        }

        PdfDictionary form = doccatalog.getAsDictionary(PdfName.AcroForm);
        if (form != null) {
            PdfArray fields = doccatalog.getAsArray(PdfName.Fields);
            for (int j = 0; j < fields.size(); ++j)
                checkFormFieldJavascripts(fields.getAsDictionary(j), strip);
        }

        for (int p = 1; p <= thepdf.getNumberOfPages(); ++p) {
            PdfDictionary page = thepdf.getPage(p).getPdfObject();
            aa = page.getAsDictionary(PdfName.AA);
            if (aa != null)
                checkJavascripts(aa, strip, " on page " + p);
            PdfArray annots = page.getAsArray(PdfName.Annots);
            if (annots != null)
                for (int j = 0; j < annots.size(); j++) {
                    PdfDictionary annot = annots.getAsDictionary(j);
                    aa = annot.getAsDictionary(PdfName.AA);
                    if (aa != null)
                        checkJavascripts(aa, strip, " in page " + p + " annotation");
                }
        }
    }
    private void checkFormFieldJavascripts(PdfDictionary field, boolean strip) {
        PdfDictionary aa = field.getAsDictionary(PdfName.AA);
        if (aa != null)
            checkJavascripts(aa, strip, " in form");

        PdfArray kids = field.getAsArray(PdfName.Kids);
        for (int j = 0; j < kids.size(); ++j)
            checkFormFieldJavascripts(kids.getAsDictionary(j), strip);
    }
    private void checkJavascripts(PdfDictionary holder, boolean strip, String where) {
        for (PdfName key : holder.keySet()) {
            PdfDictionary value = holder.getAsDictionary(key);
            PdfObject stype = null;
            if (value != null)
                stype = value.get(PdfName.S);
            if (stype == null || !stype.isName())
                continue;
            PdfName sname = (PdfName) stype;
            if (stype == PdfName.JavaScript)
                recordJavascript(holder, key, strip, where);
            else if (stype == PdfName.Rendition && value.get(PdfName.JS) != null)
                recordJavascript(value, PdfName.JS, strip, where + " [rendition]");
            else if (stype == PdfName.ImportData)
                recordJavascript(holder, key, strip, where + " [import data]");
        }
    }
    private void recordJavascript(PdfDictionary holder, PdfName key, boolean strip, String where) {
        if (strip) {
            documentModified = true;
            holder.remove(key);
        }
        addError(ERR_JAVASCRIPT, (strip ? "Stripping " : "Document contains ") + "JavaScript actions" + where + ".");
    }

    public void checkAnonymity(boolean strip) {
        PdfDictionary trailer = thepdf.getTrailer();
        PdfDictionary info = trailer != null ? trailer.getAsDictionary(PdfName.Info) : null;
        PdfString author = null;
        if (info != null)
            author = info.getAsString(PdfName.Author);
        if (author != null && !author.toString().equals("")) {
            if (strip) {
                documentModified = true;
                info.remove(PdfName.Author);
            }
            addError(ERR_ANONYMITY, (strip ? "Stripping " : "Document contains ") + "author metadata “" + author.toString() + "”; submissions should be anonymous.");
        }
        // XXX should also strip all XMP metadata but fuck it
    }
}
