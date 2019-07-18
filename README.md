HotAnalPDF
==========

HotAnalPDF is a PDF analysis and transformation application designed for the
needs of [HotCRP][].

Building requires Java, [Apache Maven][], and [iText 7][].

```
$ mvn package
$ java -jar target/hotanalpdf.jar --help
usage: hotanalpdf [-A] [--add-blank <N>] [--bmargin <arg>] [--cfoot <arg>]
       [--embed-fonts] [-F] [--footer-font] [--footer-rule] [--footer-size]
       [--help] [-j] [-J] [--lefoot <arg>] [--lfoot <arg>] [--lmargin <arg>]
       [--lofoot <arg>] [-o <FILE>] [-p <PAGENO>] [--refoot <arg>] [--rfoot
       <arg>] [--rmargin <arg>] [--rofoot <arg>] [--roman] [-s]
       [--skip-pagination <N>] [--two-sided] [--unmodified-status <STATUS>]
Check and/or paginate PDFs for HotCRP.
-A,--check-anonymous           check metadata for anonymity
   --add-blank <N>             add N blank pages at end
   --bmargin <arg>             bottom margin [28]
   --cfoot <arg>               center footer
   --embed-fonts               embed missing fonts when possible
-F,--check-fonts               check font embedding
   --footer-font               footer font file [Times Roman]
   --footer-rule               footer rule POSITION[,WIDTH] in points
   --footer-size               footer size [9]
   --help                      print this message
-j,--json                      write JSON output to stdout
-J,--check-javascript          check for JavaScript actions
   --lefoot <arg>              left-hand footer, even-numbered pages
   --lfoot <arg>               left-hand footer
   --lmargin <arg>             left margin [54]
   --lofoot <arg>              left-hand footer, odd-numbered pages
-o,--output <FILE>             write output PDF to FILE
-p,--paginate <PAGENO>         paginate starting at PAGENO
   --refoot <arg>              right-hand footer, even-numbered pages
   --rfoot <arg>               right-hand footer
   --rmargin <arg>             right margin [54]
   --rofoot <arg>              right-hand footer, odd-numbered pages
   --roman                     paginate in lowercase Roman numerals
-s,--strip                     strip JS/metadata
   --skip-pagination <N>       don't paginate first N pages
   --two-sided                 swap left/right foot/margin on even pages
   --unmodified-status <STATUS>exit status if input unmodified

Please report issues at https://github.com/kohler/hotanalpdf
```

HotAnalPDF is distributed under the
[GNU Affero General Public License, version 3][license], in accordance with iText.

[HotCRP]: https://hotcrp.com/
[Apache Maven]: https://maven.apache.org/
[iText 7]: https://itextpdf.com/
[license]: https://www.gnu.org/licenses/agpl-3.0.en.html
