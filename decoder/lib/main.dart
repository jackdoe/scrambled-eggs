import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:unscramble_qrcode_reader/qrcode_reader.dart';

int newLineN = "\n".codeUnitAt(0);
int newLineR = "\r".codeUnitAt(0);
int tabChar = "\t".codeUnitAt(0);
int spaceChar = " ".codeUnitAt(0);
const String FORCE_AUTO_FOCUS_KEY = "forceAutoFocus";
const String ENABLE_TORCH_KEY = "enableTorch";
const String AUTO_FOCUS_INTERVAL_KEY = "autoFocusInterval";

List<int> lastLines(List<int> buf, int n) {
  List<int> out = new List();
  for (int i = buf.length - 1; i >= 0; i--) {
    int c = buf[i];
    out.add(c);

    if (c == newLineN || c == newLineR) {
      if (n-- <= 0) {
        break;
      }
    }
  }
  return out.reversed.toList();
}

List<int> addPreviousLinesToEnd(int n, List<List<int>> out, List<int> current) {
  if (out.length > 0) {
    List<int> last = lastLines(out[out.length - 1], n);
    last.addAll(current);
    current = last;
  }
  return current;
}

void addChar(int c, List<int> current) {
  if (c == tabChar) {
    // Text is RichText and apparently it is not so rich to display tabs properly
    // so just replace tabs with 8 spaces, old school
    for (int k = 0; k < 8; k++) {
      current.add(spaceChar);
    }
  } else {
    current.add(c);
  }
}

List<String> getBufferData(List<int> bytes, int nBytesSplit) {
  List<List<int>> out = new List();

  int left = nBytesSplit;
  List<int> current = new List();

  for (int i = 0; i < bytes.length; i++) {
    int c = bytes[i];
    if (left > 0) {
      addChar(c, current);
      left--;
    } else {
      // split to closest new line
      int j = i;
      for (; j < bytes.length; j++) {
        int nextC = bytes[j];
        addChar(nextC, current);

        if (nextC == newLineN || nextC == newLineR) {
          break;
        }
      }
      i = j;

      // make sure each page has few lines from the previous page
      current = addPreviousLinesToEnd(5, out, current);

      out.add(current);
      current = new List();
      left = nBytesSplit;
    }
  }
  if (current.length > 0) {
    current = addPreviousLinesToEnd(5, out, current);

    out.add(current);
  }

  List<String> transformed = new List();
  out.forEach((f) {
    transformed.add(new String.fromCharCodes(f));
  });
  return transformed;
}

Future<List<String>> getFileData(String path, int nBytesSplit) async {
  return await rootBundle.load(path).then((b) {
    return getBufferData(b.buffer.asUint8List(), nBytesSplit);
  });
}

void main() => runApp(QrApp());

class TextLine extends StatelessWidget {
  final String text;
  final FontWeight fontWeight;
  final double size;

  TextLine(this.text, this.fontWeight, this.size);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: new TextStyle(
        fontFamily: "terminus",
        fontSize: this.size,
        fontWeight: fontWeight,
      ),
    );
  }
}

class Loading extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return new Scaffold(
        body: Center(child: TextLine("loading...", FontWeight.bold, 18)));
  }
}

class QrApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return new MaterialApp(
      title: 'Unscramble Eggs',
      home: new QrPage(),
    );
  }
}

class QrPage extends StatefulWidget {
  QrPage({Key key, this.title}) : super(key: key);

  final String title;

  @override
  _QrPageState createState() => new _QrPageState();
}

class _QrPageState extends State<QrPage> {
  List<FileSystemEntity> downloaded;

  Future<Item> scan() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    int autofocusInterval = (prefs.getInt(AUTO_FOCUS_INTERVAL_KEY) ?? 1000);
    bool forceAutoFocus = (prefs.getBool(FORCE_AUTO_FOCUS_KEY) ?? true);
    bool enableTorch = (prefs.getBool(ENABLE_TORCH_KEY) ?? false);
    return new QRCodeReader()
        .setForceAutoFocus(forceAutoFocus)
        .setAutoFocusIntervalInMs(autofocusInterval)
        .setTorchEnabled(enableTorch)
        .setHandlePermissions(true)
        .setExecuteAfterPermissionGranted(true)
        .scan()
        .then((code) {
      return code;
    }).catchError((onError) {
      print(onError);
    });
  }

  Future<Directory> getDirectory() async {
    var directory = await getApplicationDocumentsDirectory();
    var path = directory.path;
    directory = new Directory('$path/down');
    directory.createSync(recursive: true);
    return directory;
  }

  Future<List<FileSystemEntity>> listFiles() async {
    final directory = await getDirectory();
    Stream<FileSystemEntity> files =
        await directory.list(recursive: false, followLinks: false);
    List<FileSystemEntity> f = new List();
    await files.forEach((e) {
      f.add(e);
    });
    f.sort((a, b) {
      return a.path.compareTo(b.path);
    });
    return f;
  }

  List<int> loadFile(FileSystemEntity f) {
    return File(f.path).readAsBytesSync();
  }

  Future saveFile(String name, List<int> data) async {
    final directory = await getDirectory();
    var path = directory.path;
    File f = new File('$path/$name');
    return f.writeAsBytes(data, flush: true);
  }

  Future deleteFile(FileSystemEntity f) async {
    f.deleteSync(recursive: false);
    return listFiles().then((files) {
      setState(() {
        downloaded = files;
      });
    });
  }

  void doScan() {
    scan().then((item) {
      if (item == null) {
        return null;
      }
      var name = new DateTime.now().millisecondsSinceEpoch / 1000;

      var fn = name.toStringAsFixed(0) +
          "@" +
          item.name.replaceAll(new RegExp(r'[^\w+\.]+'), "_");
      return saveFile(fn, item.data.buffer.asUint8List());
    }).then((v) {
      return listFiles();
    }).then((files) {
      setState(() {
        downloaded = files;
      });
    });
  }

  @override
  void initState() {
    super.initState();
    listFiles().then((f) {
      setState(() {
        downloaded = f;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    if (downloaded == null) {
      return Loading();
    }

    return new Scaffold(
      floatingActionButton: ButtonBar(
          mainAxisSize: MainAxisSize.max,
          alignment: MainAxisAlignment.spaceAround,
          children: <Widget>[
            new FlatButton(
              child: TextLine("settings", FontWeight.bold, 14),
              onPressed: () {
                Navigator.push(
                    context,
                    MaterialPageRoute(
                        builder: (context) => new SettingsPage()));
              },
            ),
            new FlatButton(
              child: TextLine("unscramble", FontWeight.bold, 18),
              onPressed: () {
                doScan();
              },
            ),
          ]),
      body: ListView.builder(
        itemBuilder: (BuildContext context, int index) {
          var f = downloaded[index];
          var name = getName(f);
          return ListTile(
              title: TextLine(name, FontWeight.bold, 18),
              onLongPress: () {
                _showDeleteDialog(f);
              },
              onTap: () {
                Navigator.push(
                    context,
                    MaterialPageRoute(
                        builder: (context) => new BookPage(item: loadFile(f))));
              });
        },
        itemCount: downloaded.length,
      ),
    );
  }

  String getName(FileSystemEntity f) {
    return f.path.replaceAll(f.parent.path + "/", "").replaceAll("@", " ") +
        " " +
        (f.statSync().size / 1024).toStringAsFixed(1) +
        "kb";
  }

  void _showDeleteDialog(FileSystemEntity f) {
    // deleteFile(f);
    var name = getName(f);
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: TextLine("Are you sure?", FontWeight.bold, 18),
          content:
              TextLine("you want to delete " + name, FontWeight.normal, 14),
          actions: <Widget>[
            new FlatButton(
              child: TextLine("Delete", FontWeight.bold, 16),
              textColor: Colors.red,
              onPressed: () {
                Navigator.of(context).pop();
                deleteFile(f);
              },
            ),
            new FlatButton(
              child: TextLine("Cancel", FontWeight.bold, 16),
              textColor: Colors.black26,
              onPressed: () {
                Navigator.of(context).pop();
              },
            ),
          ],
        );
      },
    );
  }
}

class BookPage extends StatefulWidget {
  final List<int> item;

  BookPage({Key key, this.item}) : super(key: key);

  @override
  BookState createState() => BookState(item);
}

class BookState extends State<BookPage> {
  List<String> book;
  final List<int> item;
  int currentPage = 0;
  double fontSize = 18;
  bool fitBox = false;
  ScrollController _scrollController;

  BookState(this.item);

  @override
  void initState() {
    super.initState();
    _scrollController = new ScrollController();
    book = getBufferData(item, 5000);
  }

  @override
  Widget build(BuildContext context) {
    if (book == null || book.length == 0) {
      return Loading();
    }

    var buttons = <Widget>[
      new IconButton(
          onPressed: () {
            setState(() {
              if (currentPage > 0) {
                currentPage--;
              }
            });
            _scrollController
                .jumpTo(_scrollController.position.maxScrollExtent);
          },
          padding: EdgeInsets.all(2.0),
          icon: TextLine("<", FontWeight.bold, fontSize - 4)),
      new IconButton(
          onPressed: () {
            // when pressed we will open menu to add notes
            // for now just do nothing
            setState(() {
              fitBox = !fitBox;
            });
          },
          padding: EdgeInsets.all(0.0),
          iconSize: 18,
          icon: TextLine(
              (currentPage + 1).toString() + "/" + book.length.toString(),
              FontWeight.bold,
              fontSize - 4)),
      new IconButton(
          onPressed: () {
            setState(() {
              currentPage = (currentPage + 1) % book.length;
            });
            _scrollController.jumpTo(0);
          },
          padding: EdgeInsets.all(2.0),
          icon: TextLine(">", FontWeight.bold, fontSize - 4)),
    ];

    var textLine = TextLine(book[currentPage], FontWeight.normal, fontSize);
    var fittedText = new FittedBox(fit: BoxFit.fitWidth, child: textLine);

    return new Scaffold(
      floatingActionButton: ButtonBar(
        mainAxisSize: MainAxisSize.min,
        alignment: MainAxisAlignment.end,
        children: buttons,
      ),
      body: new SingleChildScrollView(
        controller: _scrollController,
        child: new SafeArea(
          child: new Container(
            child: fitBox ? textLine : fittedText,
            margin: const EdgeInsets.all(4.0),
            padding: const EdgeInsets.all(4.0),
          ),
        ),
      ),
    );
  }
}

class SettingsPage extends StatefulWidget {
  SettingsPage({Key key}) : super(key: key);

  @override
  SettingsState createState() => SettingsState();
}

class SettingsState extends State<SettingsPage> {
  int autoFocusInterval;
  bool forceAutoFocus;
  bool enableTorch;
  bool loaded = false;

  @override
  void initState() {
    SharedPreferences.getInstance().then((prefs) {
      setState(() {
        autoFocusInterval = (prefs.getInt(AUTO_FOCUS_INTERVAL_KEY) ?? 200);
        forceAutoFocus = (prefs.getBool(FORCE_AUTO_FOCUS_KEY) ?? true);
        enableTorch = (prefs.getBool(ENABLE_TORCH_KEY) ?? false);
        loaded = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    if (loaded == false) {
      return Loading();
    }
    return new Scaffold(
      body: ListView(children: <Widget>[
        ListTile(
          title: TextLine("Force Auto Focus", FontWeight.normal, 16),
          trailing: Checkbox(
              value: forceAutoFocus,
              onChanged: (val) {
                SharedPreferences.getInstance().then((prefs) {
                  prefs.setBool(FORCE_AUTO_FOCUS_KEY, val);
                }).then((x) {
                  setState(() {
                    forceAutoFocus = val;
                  });
                });
              }),
        ),
        ListTile(
          title: TextLine("Enable Torch", FontWeight.normal, 16),
          trailing: Checkbox(
              value: enableTorch,
              onChanged: (val) {
                SharedPreferences.getInstance().then((prefs) {
                  prefs.setBool(ENABLE_TORCH_KEY, val);
                }).then((x) {
                  setState(() {
                    enableTorch = val;
                  });
                });
              }),
        ),
        ListTile(
          title: TextFormField(
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                prefixIcon:
                    TextLine("Auto Focus Interval:", FontWeight.normal, 16),
              ),
              initialValue: autoFocusInterval.toString(),
              onFieldSubmitted: (input) {
                int _value = num.tryParse(input);
                if (_value > 10) {
                  SharedPreferences.getInstance().then((prefs) {
                    prefs.setInt(AUTO_FOCUS_INTERVAL_KEY, _value);
                  }).then((x) {
                    setState(() {
                      autoFocusInterval = _value;
                    });
                  });
                }

              },
          )
        )
      ]),
    );
  }
}
