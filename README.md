# AntiXray
[![](http://i.loli.net/2019/01/20/5c43dec8235d0.png)](http://www.mcbbs.net/thread-838490-1-1.html "假矿")
Anti X-Ray cheat plugin for Nukkit

Please see [mcbbs](http://www.mcbbs.net/thread-838490-1-1.html) for more information.
## Permissions
| Permission | Description |
| - | - |
| antixray.whitelist | Allow player to cheat with X-Ray |
## config.yml
```yaml
#The smaller the value, the higher the performance (1-255)
scan-height-limit: 255
#Save a serialized copy of the chunk in memory for faster sending
cache-chunks: false
#Set this to false to use hidden mode (default)
obfuscator-mode: false
#The fake block is used to replace ores in different dimensions (hidden mode only)
overworld-fake-block: 1
nether-fake-block: 87
#Worlds that need to be protected
protect-worlds:
  - "world"
#Blocks that need to be hidden
ores:
  - 14
  - 15
  - 16
  - 21
  - 56
  - 73
  - 74
  - 129
  - 153
#Such as transparent blocks and non-full blocks
filters:
  - 0
  - 6
  - 8
  - 9
  - 10
  - 11
  - 18
  - 20
  - 26
  - 27
  - 28
  - 29
  - 30
  - 31
  - 32
  - 33
  - 34
  - 37
  - 38
  - 39
  - 40
  - 44
  - 50
  - 51
  - 52
  - 53
  - 54
  - 55
  - 59
  - 60
  - 63
  - 64
  - 65
  - 66
  - 67
  - 68
  - 69
  - 70
  - 71
  - 72
  - 75
  - 76
  - 77
  - 78
  - 79
  - 81
  - 83
  - 85
  - 88
  - 90
  - 92
  - 93
  - 94
  - 95
  - 96
  - 101
  - 102
  - 104
  - 105
  - 106
  - 107
  - 108
  - 109
  - 111
  - 113
  - 114
  - 115
  - 116
  - 117
  - 118
  - 119
  - 120
  - 122
  - 126
  - 127
  - 128
  - 130
  - 131
  - 132
  - 134
  - 135
  - 136
  - 138
  - 139
  - 140
  - 141
  - 142
  - 143
  - 144
  - 145
  - 146
  - 147
  - 148
  - 149
  - 150
  - 151
  - 154
  - 156
  - 158
  - 160
  - 161
  - 163
  - 164
  - 165
  - 166
  - 167
  - 171
  - 175
  - 176
  - 177
  - 178
  - 180
  - 182
  - 183
  - 184
  - 185
  - 186
  - 187
  - 190
  - 191
  - 193
  - 194
  - 195
  - 196
  - 197
  - 198
  - 199
  - 200
  - 202
  - 203
  - 204
  - 205
  - 207
  - 208
  - 218
  - 230
  - 238
  - 239
  - 240
  - 241
  - 244
  - 250
  - 253
  - 254
```
