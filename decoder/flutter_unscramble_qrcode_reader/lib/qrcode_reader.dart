import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/services.dart';

class Item {
  Uint8List data;
  String contentType;
  String name;

  Item.fromMap(Map c)
      : contentType = c["contentType"],
        name = c["name"],
        data = new Uint8List.fromList(c["data"].cast<int>());
}

class QRCodeReader {
  static const MethodChannel _channel = const MethodChannel('qrcode_reader');

  int _autoFocusIntervalInMs = 5000;
  bool _forceAutoFocus = false;
  bool _torchEnabled = false;
  bool _handlePermissions = true;
  bool _executeAfterPermissionGranted = true;

  QRCodeReader setAutoFocusIntervalInMs(int autoFocusIntervalInMs) {
    _autoFocusIntervalInMs = autoFocusIntervalInMs;
    return this;
  }

  QRCodeReader setForceAutoFocus(bool forceAutoFocus) {
    _forceAutoFocus = forceAutoFocus;
    return this;
  }

  QRCodeReader setTorchEnabled(bool torchEnabled) {
    _torchEnabled = torchEnabled;
    return this;
  }

  QRCodeReader setHandlePermissions(bool handlePermissions) {
    _handlePermissions = handlePermissions;
    return this;
  }

  QRCodeReader setExecuteAfterPermissionGranted(
      bool executeAfterPermissionGranted) {
    _executeAfterPermissionGranted = executeAfterPermissionGranted;
    return this;
  }

  Future<Item> scan() async {
    Map params = <String, dynamic>{
      "autoFocusIntervalInMs": _autoFocusIntervalInMs,
      "forceAutoFocus": _forceAutoFocus,
      "torchEnabled": _torchEnabled,
      "handlePermissions": _handlePermissions,
      "executeAfterPermissionGranted": _executeAfterPermissionGranted,
    };

    dynamic v = await _channel.invokeMethod('readQRCode', params);
    if (v == null) {
      return null;
    }
    Item item = Item.fromMap(v);
    return item;
  }
}
