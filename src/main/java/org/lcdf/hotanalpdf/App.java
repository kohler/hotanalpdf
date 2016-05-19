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
import com.itextpdf.text.pdf.PdfObject;
import com.itextpdf.text.pdf.PdfIndirectReference;
import com.itextpdf.text.pdf.PRIndirectReference;
import com.itextpdf.text.pdf.PdfStream;
import com.itextpdf.text.pdf.PdfString;
import com.itextpdf.text.pdf.PdfArray;
import com.itextpdf.text.pdf.PdfPage;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.FontFactory;
 
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
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
    private JsonArrayBuilder fmtErrors = Json.createArrayBuilder();
    private TreeSet<String> fmtErrorsGiven = new TreeSet<String>();
    private int errorTypes = 0;
    private TreeSet<String> viewedFontRefs = new TreeSet<String>();
    private TreeSet<String> type3FontNames = new TreeSet<String>();
    private TreeSet<String> nonEmbeddedFontNames = new TreeSet<String>();
    private boolean documentModified = false;
    private boolean jsonOutput = false;

    public final int ERR_FONT_TYPE3 = 1;
    public final int ERR_FONT_NOTEMBEDDED = 2;
    public final int ERR_JAVASCRIPT = 4;
    public final int ERR_ANONYMITY = 8;
    public final String errfNameMap[] = {null, "fonttype", "fontembed", null, "javascript", null, null, null, "authormeta"};

    public class AppArgs {
        public boolean paginate = false;
        public int firstPage = 0;
        public int pageNumberSize = 9;
        public String inputFile = "-";
        public boolean outputFileGiven = false;
        public String outputFile = "-";
        public boolean jsonOutput = false;
        public boolean checkFonts = false;
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
            if (jsonOutput)
                fmtErrors.add(Json.createArrayBuilder().add(errfNameMap[errorType]).add(error).build());
            else
                System.err.println(error);
        }
    }

    public AppArgs parseArgs(String[] args) {
        Options options = new Options();
        options.addOption(Option.builder("o").longOpt("output").desc("write output PDF to FILE")
                          .hasArg(true).argName("FILE").build());
        options.addOption(Option.builder("j").longOpt("json").desc("write JSON output to stdout").build());
        options.addOption(Option.builder("F").longOpt("check-fonts").desc("check font embedding").build());
        options.addOption(Option.builder("J").longOpt("check-javascript").desc("check for JavaScript actions").build());
        options.addOption(Option.builder("A").longOpt("check-anonymous").desc("check metadata for anonymity").build());
        options.addOption(Option.builder("s").longOpt("strip").desc("strip JS/metadata").build());
        options.addOption(Option.builder("p").longOpt("paginate").desc("paginate starting at PAGENO")
                          .hasArg(true).argName("PAGENO").build());
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
            if (cl.hasOption('F'))
                appArgs.checkFonts = true;
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

    public void runMain(String[] args) throws IOException, DocumentException, NumberFormatException {
        AppArgs aa = parseArgs(args);
        jsonOutput = aa.jsonOutput;

        PdfReader reader;
        if (aa.inputFile.equals("-"))
            reader = new PdfReader(System.in);
        else
            reader = new PdfReader(aa.inputFile);

        if (aa.checkFonts)
            checkFonts(reader);
        if (aa.checkJS)
            checkJavascripts(reader, aa.strip);
        if (aa.checkAnonymity)
            checkAnonymity(reader, aa.strip);

        PdfStamper stamper = null;
        if (aa.paginate || aa.outputFileGiven) {
            java.io.OutputStream output;
            if (aa.outputFile.equals("-"))
                output = System.out;
            else
                output = new FileOutputStream(aa.outputFile);
            stamper = new PdfStamper(reader, output);
        }

        if (aa.paginate)
            paginate(aa, reader, stamper);

        if (stamper != null)
            stamper.close();
        reader.close();

        if (jsonOutput) {
            JsonObjectBuilder result = Json.createObjectBuilder()
                .add("ok", true).add("at", (long) (System.currentTimeMillis() / 1000L))
                .add("npages", reader.getNumberOfPages());
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
            System.out.println(stWriter.toString());
        }

        System.exit(errorTypes == 0 ? 0 : 1);
    }

    public void paginate(AppArgs aa, PdfReader reader, PdfStamper stamper) throws IOException, DocumentException {
        java.io.InputStream numberFontStream = this.getClass().getResourceAsStream("/HotCRPNumberTime.otf");
        byte[] numberFontBytes = IOUtils.toByteArray(numberFontStream);
        BaseFont numberFont = BaseFont.createFont("HotCRPNumberTime.otf", BaseFont.WINANSI, true, false, numberFontBytes, null);
        Font font = new Font(numberFont, aa.pageNumberSize);

        for (int p = 1; p <= reader.getNumberOfPages(); ++p) {
            Phrase pageno = new Phrase(Integer.toString(aa.firstPage + p - 1), font);
            ColumnText.showTextAligned(
                stamper.getOverContent(p), Element.ALIGN_CENTER,
                pageno, reader.getPageSize(p).getWidth() / 2, 28, 0);
            documentModified = true;
        }
    }

    public void checkFonts(PdfReader reader) throws IOException, DocumentException {
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
    static public String friendlyFontName(String fontName) {
        return fontName.replace("#20", " ");
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
            namestr = name.toString().substring(1);

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
            String fnamestr = friendlyFontName(namestr);
            if (!type3FontNames.contains(fnamestr)) {
                type3FontNames.add(fnamestr);
                if (fnamestr.equals("[no name]"))
                    addError(ERR_FONT_TYPE3, "document contains unnamed Type3 fonts (first referenced on page " + p + ")");
                else
                    addError(ERR_FONT_TYPE3, "document contains Type3 font “" + friendlyFontName(namestr) + "” (first referenced on page " + p + ")");
            }
        } else if (embedded_type == null) {
            String fnamestr = friendlyFontName(namestr);
            if (!nonEmbeddedFontNames.contains(fnamestr)) {
                nonEmbeddedFontNames.add(fnamestr);
                addError(ERR_FONT_NOTEMBEDDED, claimed_type.toString().substring(1) + " font “" + fnamestr + "” not embedded (first referenced on page " + p + ")");
            }
        }
    }

    public void checkJavascripts(PdfReader reader, boolean strip) throws IOException, DocumentException {
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
        addError(ERR_JAVASCRIPT, (strip ? "stripping " : "document contains ") + "JavaScript actions" + where);
    }

    public void checkAnonymity(PdfReader reader, boolean strip) {
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
            addError(ERR_ANONYMITY, (strip ? "stripping " : "document contains ") + "author metadata “" + author.toString() + "” (submissions should be anonymous)");
        }
        // XXX should also strip all XMP metadata but fuck it
    }
}
