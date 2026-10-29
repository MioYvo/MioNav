# MioNav wire protocol v1

Little-endian throughout. Implementation: `core/proto`. Tests: `core/proto/src/test`.

## Frame

```
offset size field
0      2    magic 0x4D 0x48 ("MH")
2      1    version = 1
3      1    type
4      1    flags
5      2    seq
7      2    len      payload length
9      2    crc16    CRC-16/CCITT-FALSE over bytes[3..9) then payload
11     len  payload
```

- CRC-16/CCITT-FALSE: poly `0x1021`, init `0xFFFF`, no reflection, no final xor.
  Header bytes 3..8 and the payload are two separate ranges, so the CRC is computed in two steps
  (`Crc16.of` then `Crc16.update`).
- `tryDecode` returns null on any error; `decode` throws `ProtocolException`.

### Types

| code | name | direction | notes |
| --- | --- | --- | --- |
| 0x01 | HELLO | both | version, screen, capabilities |
| 0x02 | NAV | phone → watch | 1 Hz, no ACK |
| 0x03 | STR | phone → watch | string-table delta |
| 0x04 | SCENE | phone → watch | polyline display list |
| 0x05 | ACK | both | sequence confirmation |
| 0x06 | HINT | watch → phone | thermal / bandwidth downgrade |
| 0x07 | BYE | both | clean shutdown |
| 0x08 | ROUTE | phone → watch | reserved |

### Flags

| bit | meaning |
| --- | --- |
| 0 | chunked |
| 1 | ack required |

## Chunking

A chunked payload starts with 4 bytes:

```
msgId u16 | index u8 | total u8 | body
```

`Chunker.chunk(frame, maxPayload)` where `maxPayload = mtu - 3 - 11`.
`Reassembler` holds partials for 5 s, rejects mixed totals, and reassembles out of order.

## HELLO (pack handshake)

Both directions share one layout. Empty strings mean "nothing to say": a watch without a pack sends
no id and no version, a phone that will not serve sends no url.

```
u8  version      payload version, currently 1
u8  flags        bit 0 = sender holds a pack
u8  idLen
u8  versionLen
u32 packBytes    0 when unknown
u32 packTiles    0 when unknown
u8  shaLen
u8  urlLen
     id (idLen)          UTF-8, at most 63
     version (versionLen) UTF-8, at most 31
     sha256 (shaLen)      hex, at most 64
     url (urlLen)         UTF-8, at most 127
```

The lengths are capped so the whole payload fits one frame next to an empty chunk header. The
fields in the diagram are ordered as they appear on the wire; the four variable-length blobs follow
the fixed part in that order. `HelloFrame` enforces the same limits when it encodes.

The phone decides with `docs/design.md` §12.1: no local pack → serve; same pack → URL stays empty;
different → serve the newer one. The watch treats an empty URL as "nothing to do" and keeps its
current pack.

## NAV (48 bytes)

| offset | type | field | unit |
| --- | --- | --- | --- |
| 0 | u8 | iconType | Amap raw |
| 1 | u8 | flags | NAVIGATING 0x01, GPS_WEAK 0x02, RECALCULATING 0x04, ARRIVED 0x08, HAS_SERVICE_AREA 0x10, HAS_LANES 0x20 |
| 2 | u16 | stepDistM | m |
| 4 | u16 | remainDist10m | 10 m |
| 6 | u16 | remainTimeMin | min |
| 8 | u8 | speedKmh | km/h |
| 9 | u8 | limitKmh | km/h, 0 = unknown |
| 10 | u16 | svcDist100m | 100 m |
| 12 | u16 | roadId | string table id |
| 14 | u16 | nextRoadId | string table id |
| 16 | u8 | laneUsable | bitmap |
| 17 | u8 | laneRec | bitmap |
| 18 | u16 | cameraDistM | m |
| 20 | u8 | cameraType | Amap raw |
| 21 | u8 | crossFlag | junction view available |
| 22 | u16 | etaMin | minutes of day |
| 24 | u16 | sceneVersion | last SCENE sent |
| 26 | u32 | tsMs | sender monotonic low 32 bits |
| 30 | 4 | latE7 | WGS84 latitude × 1e7, 0 = no fix |
| 34 | 4 | lonE7 | WGS84 longitude × 1e7 |
| 38 | 2 | bearingDeg100 | course over ground, degrees × 100 |
| 40 | 8 | reserved | zero |

## SCENE

```
u8   version
u8   themeId
u32  originLatE7
u32  originLonE7
u16  radiusM
u16  count
repeat count:
  u8  cls      0 major, 1 secondary, 2 minor, 3 waterway, 4 water, 5 route
  u8  nPts     1..255
  nPts * (i16 dx, i16 dy)   metres relative to the origin
```

Budget: 300 lines × 6 points × 4 B ≈ 7.2 KB. Coordinates are clamped to ±32 000 m.

Since the watch builds scenes from its own MBTiles, SCENE is only used when the phone has the tile
pack and the watch does not. The NAV frame carries the fix, so both paths agree on the origin.

## STR

```
u8  count
repeat:
  u16 id        ids start at 1, 0 means "no name"
  u8  len       UTF-8 bytes, 1..120
  len bytes text
```

Ids are append-only per session; the watch asks for nothing, the phone sends everything after the
last id it already sent.

## Golden bytes

`Frame(MsgType.HELLO, seq=1, payload=[1,2,3])` encodes to:

```
4D 48 01 01 00 01 00 03 00  <crc lo> <crc hi>  01 02 03
```

The CRC is asserted by `Crc16Test` against the published check value `0x29B1` for `"123456789"`.
