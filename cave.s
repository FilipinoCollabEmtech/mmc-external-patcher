    .text
    .global cave
    .type cave, %function
// Minimal cave: capture the Joypad pointer (x0) only. No calls into game code.
cave:
    mov     x16, x30
    ldr     x17, .Lretaddr
    sub     x16, x16, x17          // x16 = library base
    ldr     x18, .Ljoypad
    add     x18, x18, x16
    str     x0, [x18]
    ret
    .ltorg
.Lretaddr: .quad 0x00000000006888f4
.Ljoypad:  .quad 0x00000000013f4780
