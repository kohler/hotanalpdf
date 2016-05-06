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
import com.itextpdf.text.pdf.PdfArray;
import com.itextpdf.text.pdf.PdfPage;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.FontFactory;
 
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.TreeSet;

import org.apache.commons.io.IOUtils;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.HelpFormatter;

 
public class App {
    private TreeSet<String> viewed_font_refs;
    public static final String SRC = "x.pdf";
    public static final String DEST = "xout.pdf";

    public static void main(String[] args) throws IOException, DocumentException, NumberFormatException, ParseException {
        new App().runMain(args);
    }

    public App() {
        viewed_font_refs = new TreeSet<String>();
    }

    public CommandLine parseArgs(String[] args) {
        Options options = new Options();
        options.addOption(Option.builder("p").longOpt("paginate").desc("paginate starting at PAGENO")
                          .hasArg(true).argName("PAGENO").build());
        options.addOption(Option.builder("o").longOpt("output").desc("write output to FILE")
                          .hasArg(true).argName("FILE").build());
        options.addOption(Option.builder().longOpt("help").desc("print this message").build());
        int status = 0;

        try {
            CommandLine cl = new DefaultParser().parse(options, args);
            if (cl.hasOption('p'))
                Integer.parseInt(cl.getOptionValue('p'));
            if (cl.getArgs().length > (cl.hasOption('o') ? 1 : 2))
                throw new NumberFormatException();
            if (!cl.hasOption("help"))
                return cl;
        } catch (Throwable e) {
            status = 1;
        }

        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("hotanalpdf", "Check and/or paginate PDFs for HotCRP.", options, "\nPlease report issues at https://github.com/kohler/hotanalpdf", true);
        System.exit(status);
        return null;
    }

    public void runMain(String[] args) throws IOException, DocumentException, NumberFormatException {
        CommandLine cl = parseArgs(args);

        int firstPage = 0;
        if (cl.hasOption('p'))
            firstPage = Integer.parseInt(cl.getOptionValue('p'));

        PdfReader reader;
        if (cl.getArgs().length == 0 || cl.getArgs()[0] == "-")
            reader = new PdfReader(System.in);
        else
            reader = new PdfReader(cl.getArgs()[0]);

        checkFonts(reader);

        PdfStamper stamper = null;
        if (cl.hasOption('p') || cl.hasOption('o') || cl.getArgs().length == 2) {
            java.io.OutputStream output;
            String outputName = "-";
            if (cl.hasOption('o'))
                outputName = cl.getOptionValue('o');
            else if (cl.getArgs().length == 2)
                outputName = cl.getArgs()[1];
            if (outputName == "-")
                output = System.out;
            else
                output = new FileOutputStream(outputName);
            stamper = new PdfStamper(reader, output);
        }

        if (cl.hasOption('p'))
            paginate(firstPage, reader, stamper);

        if (stamper != null)
            stamper.close();
        reader.close();
    }

    public void paginate(int firstPage, PdfReader reader, PdfStamper stamper) throws IOException, DocumentException {
        java.io.InputStream numberFontStream = this.getClass().getResourceAsStream("/HotCRPNumberTime.otf");
        byte[] numberFontBytes = IOUtils.toByteArray(numberFontStream);
        BaseFont numberFont = BaseFont.createFont("HotCRPNumberTime.otf", BaseFont.WINANSI, true, false, numberFontBytes, null);
        Font font = new Font(numberFont, 9);

        for (int p = 1; p <= reader.getNumberOfPages(); ++p) {
            Phrase pageno = new Phrase(Integer.toString(firstPage + p - 1), font);
            ColumnText.showTextAligned(
                stamper.getOverContent(p), Element.ALIGN_CENTER,
                pageno, reader.getPageSize(p).getWidth() / 2, 28, 0);
        }
    }

    public void checkFonts(PdfReader reader) throws IOException, DocumentException {
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
        if (viewed_font_refs.contains(refname))
            return true;
        else {
            viewed_font_refs.add(refname);
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

        System.err.println(refname + " " + namestr + " " + claimed_type.toString() + ";" + (embedded_type == null ? "not embedded" : embedded_type.toString()));
    }
}
