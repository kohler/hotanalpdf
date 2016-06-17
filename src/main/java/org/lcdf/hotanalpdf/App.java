package org.lcdf.hotanalpdf;
 
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Font.FontFamily;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfDictionary;
import com.itextpdf.text.pdf.PdfName;
import com.itextpdf.text.pdf.PdfNumber;
import com.itextpdf.text.pdf.PdfObject;
import com.itextpdf.text.pdf.PdfIndirectReference;
import com.itextpdf.text.pdf.PRIndirectReference;
import com.itextpdf.text.pdf.PdfStream;
import com.itextpdf.text.pdf.PdfString;
import com.itextpdf.text.pdf.PdfArray;
import com.itextpdf.text.pdf.PdfPage;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.FontFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringWriter;
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
    private PdfReader reader = null;
    private PdfStamper theStamper = null;
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
        private boolean loaded = false;
        public PdfIndirectReference dataReference = null;
        public PdfIndirectReference descriptorReference = null;

        public EmbedFontInfo(String baseName, String fontName, String filePrefix, boolean symbolic) {
            this.baseName = baseName;
            this.fontName = fontName;
            this.filePrefix = filePrefix;
            this.symbolic = symbolic;
        }
        public boolean load() {
            if (!loaded) {
                loaded = true;
                pfbData = getContents(filePrefix, ".pfb", "type1 fonts");
                afmData = getContents(filePrefix, ".afm", "afm");
            }
            return pfbData != null && afmData != null;
        }
        public BaseFont getBaseFont(PdfDictionary fontDict) {
            return getBaseFont(AnalEncoding.getPdfEncoding(fontDict));
        }
        public BaseFont getBaseFont(AnalEncoding encoding) {
            try {
                return BaseFont.createFont(fontName + ".afm", encoding.unparseItext(), BaseFont.EMBEDDED, false, afmData, pfbData, false, true);
            } catch (Throwable e) {
                return null;
            }
        }
        public PdfDictionary getFontDescriptor(BaseFont font) throws DocumentException {
            Class<?>[] classArray = {PdfIndirectReference.class};
            try {
                java.lang.reflect.Method method = font.getClass().getDeclaredMethod("getFontDescriptor", classArray);
                method.setAccessible(true);
                Object[] args = {dataReference};
                PdfDictionary descriptor = (PdfDictionary) method.invoke(font, args);
                String theirName = PdfName.decodeName(descriptor.getAsName(PdfName.FONTNAME).toString());
                if (!theirName.equals(fontName)) {
                    System.err.println(baseName + " replacement: expected " + fontName + ", got " + theirName);
                    fontName = theirName;
                }
                return descriptor;
            } catch (Throwable t) {
                throw new NullPointerException();
            }
        }
        public PdfStream getFullFontStream(BaseFont font) throws DocumentException {
            Class<?>[] classArray = {};
            try {
                java.lang.reflect.Method method = font.getClass().getDeclaredMethod("getFullFontStream", classArray);
                method.setAccessible(true);
                Object[] args = {};
                return (PdfStream) method.invoke(font, args);
            } catch (Throwable t) {
                throw new NullPointerException();
            }
        }
        static private byte[] getContents(String prefix, String suffix, String kind) {
            try {
                String f = findFile(prefix, suffix, kind);
                if (f != null) {
                    java.io.InputStream fileStream = new java.io.FileInputStream(f);
                    return IOUtils.toByteArray(fileStream);
                }
            } catch (IOException e) {
            }
            return null;
        }
        static private String findFile(String prefix, String suffix, String kind) {
            if (new java.io.File(prefix + suffix).exists())
                return prefix + suffix;
            try {
                String[] args = {"kpsewhich", "-format=" + kind, prefix + suffix};
                Process p = Runtime.getRuntime().exec(args);
                String result = IOUtils.toString(p.getInputStream(), "UTF-8").trim();
                p.destroy();
                if (result != "" && new java.io.File(result).exists())
                    return result;
            } catch (IOException e) {
            }
            int i = prefix.lastIndexOf('-');
            if (i >= 0)
                return findFile(prefix.substring(0, i), suffix, kind);
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
            String fontNameStr = font.getAsName(PdfName.BASEFONT).toString();
            if (fontNameStr == "/Symbol")
                return getEncoding("SymbolEncoding");
            else if (fontNameStr == "/ZapfDingbats")
                return getEncoding("ZapfDingbats");
            else
                return getEncoding("StandardEncoding");
        }
        static public AnalEncoding getPdfEncoding(PdfDictionary font) {
            PdfObject encodingObj = font.get(PdfName.ENCODING);
            if (encodingObj != null && encodingObj.isName())
                return getEncoding(((PdfName) encodingObj).toString().substring(1));
            else if (encodingObj != null && (encodingObj.isDictionary() || encodingObj.isIndirect())) {
                PdfDictionary encodingDict = font.getAsDict(PdfName.ENCODING);
                PdfName base = encodingDict.getAsName(PdfName.BASEENCODING);
                AnalEncoding e;
                if (base != null)
                    e = getEncoding(base.toString().substring(1)).copy();
                else
                    e = getBasePdfEncoding(font).copy();
                PdfArray differences = encodingDict.getAsArray(PdfName.DIFFERENCES);
                if (differences != null) {
                    int nextCode = -1;
                    for (int i = 0; i < differences.size(); ++i)
                        if (differences.getPdfObject(i).isNumber())
                            nextCode = differences.getAsNumber(i).intValue();
                        else if (nextCode >= 0 && nextCode < 256) {
                            e.encoding[nextCode] = PdfName.decodeName(differences.getAsName(i).toString());
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
        public int pageNumberSize = 9;
        public boolean pageNumberRoman = false;
        public String inputFile = "-";
        public boolean outputFileGiven = false;
        public String outputFile = "-";
        public boolean onlyModified = false;
        public boolean jsonOutput = false;
        public boolean checkFonts = false;
        public boolean embedFonts = false;
        public boolean checkJS = false;
        public boolean checkAnonymity = false;
        public boolean strip = false;
    }

    public static void main(String[] args) throws IOException, DocumentException, NumberFormatException, ParseException {
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
        options.addOption(Option.builder().longOpt("only-modified").desc("only write output if modified").build());
        options.addOption(Option.builder("j").longOpt("json").desc("write JSON output to stdout").build());
        options.addOption(Option.builder("F").longOpt("check-fonts").desc("check font embedding").build());
        options.addOption(Option.builder("J").longOpt("check-javascript").desc("check for JavaScript actions").build());
        options.addOption(Option.builder("A").longOpt("check-anonymous").desc("check metadata for anonymity").build());
        options.addOption(Option.builder("s").longOpt("strip").desc("strip JS/metadata").build());
        options.addOption(Option.builder().longOpt("embed-fonts").desc("embed missing fonts when possible").build());
        options.addOption(Option.builder("p").longOpt("paginate").desc("paginate starting at PAGENO")
                          .hasArg(true).argName("PAGENO").build());
        options.addOption(Option.builder().longOpt("roman").desc("paginate in lowercase Roman numberals").build());
        options.addOption(Option.builder().longOpt("page-number-size").desc("page number size [9]")
                          .hasArg(true).argName("").build());
        options.addOption(Option.builder().longOpt("help").desc("print this message").build());
        int status = 0;

        try {
            CommandLine cl = new DefaultParser().parse(options, args);
            AppArgs appArgs = new AppArgs();
            if (cl.hasOption('p')) {
                appArgs.paginate = true;
                appArgs.firstPage = Integer.parseInt(cl.getOptionValue('p'));
            }
            if (cl.hasOption("page-number-size"))
                appArgs.pageNumberSize = Integer.parseInt(cl.getOptionValue("page-number-size"));
            if (cl.hasOption("roman"))
                appArgs.pageNumberRoman = true;
            if (cl.getArgs().length > (cl.hasOption('o') ? 1 : 2))
                throw new NumberFormatException();
            if (cl.getArgs().length > 0)
                appArgs.inputFile = cl.getArgs()[0];
            if (cl.hasOption('o') || cl.getArgs().length > 1)
                appArgs.outputFileGiven = true;
            if (cl.hasOption('o'))
                appArgs.outputFile = cl.getOptionValue('o');
            else if (cl.getArgs().length > 1)
                appArgs.outputFile = cl.getArgs()[1];
            if (cl.hasOption("only-modified"))
                appArgs.onlyModified = true;
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
            if (cl.hasOption('j'))
                appArgs.jsonOutput = true;
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
        formatter.printHelp("hotanalpdf", "Check and/or paginate PDFs for HotCRP.", options, "\nPlease report issues at https://github.com/kohler/hotanalpdf", true);
        System.exit(status);
        return null;
    }

    private PdfStamper getStamper() throws IOException, DocumentException {
        if (theStamper == null) {
            java.io.OutputStream output;
            if (appArgs.outputFile.equals("-"))
                output = System.out;
            else
                output = new FileOutputStream(appArgs.outputFile);
            theStamper = new PdfStamper(reader, output);
        }
        return theStamper;
    }

    public void runMain(String[] args) throws IOException, DocumentException, NumberFormatException {
        appArgs = parseArgs(args);

        if (appArgs.inputFile.equals("-"))
            reader = new PdfReader(System.in);
        else
            reader = new PdfReader(appArgs.inputFile);

        if (appArgs.checkFonts || appArgs.embedFonts)
            checkFonts();
        if (appArgs.checkJS)
            checkJavascripts(appArgs.strip);
        if (appArgs.checkAnonymity)
            checkAnonymity(appArgs.strip);
        if (appArgs.paginate)
            paginate();

        boolean maybeModified = appArgs.paginate || appArgs.strip || appArgs.embedFonts || appArgs.outputFileGiven;
        if (maybeModified && !appArgs.onlyModified)
            getStamper();
        if (theStamper != null)
            theStamper.close();
        reader.close();

        if (appArgs.jsonOutput) {
            JsonObjectBuilder result = Json.createObjectBuilder()
                .add("ok", true).add("at", (long) (System.currentTimeMillis() / 1000L))
                .add("npages", reader.getNumberOfPages());
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

        System.exit(errorTypes == 0 ? 0 : 1);
    }

    static final private String romanNumeralOut[] = {"c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
    static final private int romanNumeralIn[] = {100, 90, 50, 40, 10, 9, 5, 4, 1};
    static public String romanNumerals(int n) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        int pos = 0;
        while (n > 0)
            if (n >= romanNumeralIn[pos]) {
                sb.append(romanNumeralOut[pos]);
                n -= romanNumeralIn[pos];
            } else
                ++pos;
        return sb.toString();
    }
    public void paginate() throws IOException, DocumentException {
        java.io.InputStream numberFontStream = this.getClass().getResourceAsStream("/HotCRPNumberTime.otf");
        byte[] numberFontBytes = IOUtils.toByteArray(numberFontStream);
        BaseFont numberFont = BaseFont.createFont("HotCRPNumberTime.otf", BaseFont.WINANSI, true, false, numberFontBytes, null);
        Font font = new Font(numberFont, appArgs.pageNumberSize);

        for (int p = 1; p <= reader.getNumberOfPages(); ++p) {
            int pageno = appArgs.firstPage + p - 1;
            String pagenoStr = appArgs.pageNumberRoman ? romanNumerals(pageno) : Integer.toString(pageno);
            Phrase pagenoPhrase = new Phrase(pagenoStr, font);
            ColumnText.showTextAligned(
                getStamper().getOverContent(p), Element.ALIGN_CENTER,
                pagenoPhrase, reader.getPageSize(p).getWidth() / 2, 28, 0);
            documentModified = true;
        }
    }

    public void checkFonts() throws IOException, DocumentException {
        // parsing/traversing borrowed from `poppler/util/pdffonts`
        for (int p = 1; p <= reader.getNumberOfPages(); ++p) {
            PdfDictionary page = reader.getPageN(p);
            PdfDictionary res = page.getAsDict(PdfName.RESOURCES);
            checkFontsDict(p, res);
            PdfArray annots = page.getAsArray(PdfName.ANNOTS);
            if (annots != null)
                for (int j = 0; j < annots.size(); j++) {
                    PdfDictionary annot = annots.getAsDict(j);
                    checkFontsDict(j, annot.getAsDict(PdfName.RESOURCES));
                }
        }
    }
    public void checkFontsDict(int p, PdfDictionary res) {
        if (res == null)
            return;
        checkFontsRefs(p, res, PdfName.XOBJECT);
        checkFontsRefs(p, res, PdfName.PATTERN);
        PdfDictionary fonts = res.getAsDict(PdfName.FONT);
        if (fonts != null) {
            for (PdfName key : fonts.getKeys())
                checkFont(p, fonts.get(key));
        }
    }
    static private String refName(PdfObject obj) {
        PdfIndirectReference ref = (PdfIndirectReference) obj;
        return ref.getNumber() + " " + ref.getGeneration();
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
        PdfDictionary xres = res.getAsDict(xkey);
        if (xres == null)
            return;
        for (PdfName key : xres.getKeys()) {
            PdfObject obj = xres.get(key);
            if (obj.isIndirect() && seenRef(obj))
                continue;
            obj = PdfReader.getPdfObject(obj);
            if (obj.isStream()) {
                PdfDictionary strdict = (PdfDictionary) obj;
                PdfDictionary strres = strdict.getAsDict(PdfName.RESOURCES);
                if (strres != null && strres != res)
                    checkFontsDict(p, strres);
            }
        }
    }
    public void checkFont(int p, PdfObject fontobj) {
        String refname = "[direct]";
        if (fontobj.isIndirect()) {
            if (seenRef(fontobj))
                return;
            refname = refName(fontobj);
            fontobj = PdfReader.getPdfObject(fontobj);
        }
        if (!fontobj.isDictionary())
            return;

        PdfDictionary font = (PdfDictionary) fontobj;
        PdfDictionary base = font;

        PdfName name = font.getAsName(PdfName.BASEFONT);
        String namestr;
        if (name == null)
            namestr = "[no name]";
        else
            namestr = PdfName.decodeName(name.toString());

        PdfName declared_type = font.getAsName(PdfName.SUBTYPE);
        boolean isType0 = declared_type != null && declared_type.equals(PdfName.TYPE0);

        PdfName descendant_type = null;
        PdfArray descendants = font.getAsArray(PdfName.DESCENDANTFONTS);
        if (descendants != null && descendants.size() > 0) {
            PdfDictionary descendant0 = descendants.getAsDict(0);
            if (descendant0 != null) {
                base = descendant0;
                descendant_type = base.getAsName(PdfName.SUBTYPE);
            }
        }

        PdfName embedded_type = null;
        PdfDictionary desc = base.getAsDict(PdfName.FONTDESCRIPTOR);
        if (desc == null) {
            if (declared_type.equals(PdfName.TYPE3)
                && base.getAsDict(PdfName.CHARPROCS) != null)
                embedded_type = PdfName.TYPE3;
            else
                embedded_type = null;
        } else if (desc.get(PdfName.FONTFILE) != null)
            embedded_type = PdfName.TYPE1;
        else if (desc.get(PdfName.FONTFILE2) != null)
            embedded_type = isType0 ? PdfName.CIDFONTTYPE2 : PdfName.TRUETYPE;
        else {
            PdfStream fontdict = desc.getAsStream(PdfName.FONTFILE3);
            if (fontdict != null)
                embedded_type = fontdict.getAsName(PdfName.SUBTYPE);
            else
                embedded_type = null;
        }

        PdfName claimed_type = embedded_type;
        if (claimed_type == null)
            claimed_type = descendant_type;
        if (claimed_type == null)
            claimed_type = declared_type;

        if (claimed_type.equals(PdfName.TYPE3)) {
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

    private boolean tryEmbed(String fontName, PdfDictionary font) {
        if (embedFontMap == null)
            makeEmbedFontMap();
        EmbedFontInfo embedFont = embedFontMap.get(fontName);
        if (embedFont == null || !embedFont.load())
            return false;

        AnalEncoding encoding = AnalEncoding.getPdfEncoding(font);
        BaseFont baseFont = embedFont.getBaseFont(encoding);
        if (baseFont == null)
            return false;

        if (embedFont.dataReference == null) {
            try {
                PdfStream stream = embedFont.getFullFontStream(baseFont);
                embedFont.dataReference = getStamper().getWriter().addToBody(stream).getIndirectReference();
            } catch (Throwable e) {
                return false;
            }
        }

        if (embedFont.descriptorReference == null) {
            try {
                PdfDictionary newDescriptor = embedFont.getFontDescriptor(baseFont);
                embedFont.descriptorReference = getStamper().getWriter().addToBody(newDescriptor).getIndirectReference();
            } catch (Throwable e) {
                return false;
            }
        }

        font.put(PdfName.BASEFONT, new PdfName(embedFont.fontName));
        font.put(PdfName.FONTDESCRIPTOR, embedFont.descriptorReference);
        if (!font.contains(PdfName.WIDTHS)) {
            int firstChar = -1, lastChar = -1;
            PdfArray widths = new PdfArray();
            int[] w = new int[1];
            for (int ch = 0; ch < 256; ++ch)
                if (encoding.encoding[ch] != null && !encoding.encoding[ch].equals(".notdef")) {
                    if (firstChar < 0)
                        firstChar = ch;
                    else {
                        w[0] = 0;
                        for (; lastChar + 1 < ch; ++lastChar)
                            widths.add(w);
                    }
                    lastChar = ch;
                    w[0] = baseFont.getWidth(AnalEncoding.unicodeFor(encoding.encoding[ch]));
                    widths.add(w);
                }
            try {
                font.put(PdfName.FIRSTCHAR, new PdfNumber(firstChar));
                font.put(PdfName.LASTCHAR, new PdfNumber(lastChar));
                font.put(PdfName.WIDTHS, getStamper().getWriter().addToBody(widths).getIndirectReference());
            } catch (Throwable e) {
                return false;
            }
        }

        /*for (PdfName n : font.getKeys())
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
        addEmbedFont("Symbol", "StandardSymL", "usyr", true);
        addEmbedFont("ZapfChancery-MediumItalic", "URWChanceryL-MediItal", "uzcmi8a");
        addEmbedFont("ZapfDingbats", "Dingbats", "uzdr", true);
    }

    public void checkJavascripts(boolean strip) throws IOException, DocumentException {
        PdfDictionary doccatalog = reader.getCatalog();
        PdfDictionary aa = doccatalog.getAsDict(PdfName.AA);
        if (aa != null)
            checkJavascripts(aa, strip, " at document level");

        PdfDictionary names = doccatalog.getAsDict(PdfName.NAMES);
        if (names != null) {
            if (names.get(PdfName.JAVASCRIPT) != null)
                recordJavascript(names, PdfName.JAVASCRIPT, strip, " in global scripts");
        }

        PdfDictionary form = doccatalog.getAsDict(PdfName.ACROFORM);
        if (form != null) {
            PdfArray fields = doccatalog.getAsArray(PdfName.FIELDS);
            for (int j = 0; j < fields.size(); ++j)
                checkFormFieldJavascripts(fields.getAsDict(j), strip);
        }

        for (int p = 1; p <= reader.getNumberOfPages(); ++p) {
            PdfDictionary page = reader.getPageN(p);
            aa = page.getAsDict(PdfName.AA);
            if (aa != null)
                checkJavascripts(aa, strip, " on page " + p);
            PdfArray annots = page.getAsArray(PdfName.ANNOTS);
            if (annots != null)
                for (int j = 0; j < annots.size(); j++) {
                    PdfDictionary annot = annots.getAsDict(j);
                    aa = annot.getAsDict(PdfName.AA);
                    if (aa != null)
                        checkJavascripts(aa, strip, " in page " + p + " annotation");
                }
        }
    }
    private void checkFormFieldJavascripts(PdfDictionary field, boolean strip) {
        PdfDictionary aa = field.getAsDict(PdfName.AA);
        if (aa != null)
            checkJavascripts(aa, strip, " in form");

        PdfArray kids = field.getAsArray(PdfName.KIDS);
        for (int j = 0; j < kids.size(); ++j)
            checkFormFieldJavascripts(kids.getAsDict(j), strip);
    }
    private void checkJavascripts(PdfDictionary holder, boolean strip, String where) {
        for (PdfName key : holder.getKeys()) {
            PdfDictionary value = holder.getAsDict(key);
            PdfObject stype = null;
            if (value != null)
                stype = value.get(PdfName.S);
            if (stype == null || !stype.isName())
                continue;
            PdfName sname = (PdfName) stype;
            if (stype == PdfName.JAVASCRIPT)
                recordJavascript(holder, key, strip, where);
            else if (stype == PdfName.RENDITION && value.get(PdfName.JS) != null)
                recordJavascript(value, PdfName.JS, strip, where + " [rendition]");
            else if (stype == PdfName.IMPORTDATA)
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
        PdfDictionary trailer = reader.getTrailer();
        PdfDictionary info = trailer != null ? trailer.getAsDict(PdfName.INFO) : null;
        PdfString author = null;
        if (info != null)
            author = info.getAsString(PdfName.AUTHOR);
        if (author != null && !author.toString().equals("")) {
            if (strip) {
                documentModified = true;
                info.remove(PdfName.AUTHOR);
            }
            addError(ERR_ANONYMITY, (strip ? "Stripping " : "Document contains ") + "author metadata “" + author.toString() + "”; submissions should be anonymous.");
        }
        // XXX should also strip all XMP metadata but fuck it
    }
}
