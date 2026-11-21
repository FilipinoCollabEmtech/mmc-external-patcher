    .text
    .global cave
    .type cave, %function
cave:
    sub     sp, sp, #0x20
    stp     x29, x30, [sp, #0x10]
    // Save incoming Joypad pointer (x0) for the triggerbot.
    ldr     x16, .Ljoypad_slot
    str     x0, [x16]
    // Call the game's own Enemy::canSeeTarget(fake enemy).
    // Returns w0 = 1 when the path is CLEAR, 0 when blocked.
    ldr     x16, .Lfake
    mov     x0, x16
    ldr     x17, .Lcansee
    blr     x17
    ldr     x16, .Llos_slot
    strb    w0, [x16]
    // Select angle: clear -> stub+8 (target angle), blocked -> stub+12 (base angle).
    ldr     x16, .Langles
    add     x17, x16, #4
    csel    x16, x16, x17, ne
    ldr     s0, [x16]
    ldp     x29, x30, [sp, #0x10]
    add     sp, sp, #0x20
    ret
    .ltorg
.Lfake:        .quad 0x00000000013f4000
.Lcansee:      .quad 0x0000000000619e2c
.Langles:      .quad 0x00000000006888f8
.Ljoypad_slot: .quad 0x00000000013f4780
.Llos_slot:    .quad 0x00000000013f4700
