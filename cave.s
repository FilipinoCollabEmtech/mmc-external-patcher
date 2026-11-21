    .text
    .global cave
    .type cave, %function
// Entered by BL from the getFireAngle stub: x30 = base + 0x6888f4.
// Calls the game's own Enemy::canSeeTarget on a synthetic enemy, selects the
// angle, and RESTORES x30 before returning (the old version did not).
cave:
    sub     sp, sp, #0x30
    stp     x29, x30, [sp, #0x20]
    stp     x19, x20, [sp, #0x10]
    mov     x19, x30
    ldr     x20, .Lretaddr
    sub     x19, x19, x20          // x19 = library base

    // Save the Joypad pointer from x0.
    ldr     x16, .Ljoypad
    add     x16, x16, x19
    str     x0, [x16]

    // Only query when external says a target is active.
    ldr     x16, .Lenable
    add     x16, x16, x19
    ldrb    w16, [x16]
    cbz     w16, .Lskip

    // Guard: chipmunk space must be non-null.
    ldr     x16, .Lspace
    add     x16, x16, x19
    ldr     x16, [x16]
    cbz     x16, .Lskip
    ldr     x16, [x16]
    cbz     x16, .Lskip

    // w0 = canSeeTarget(fake enemy): 1 = clear, 0 = blocked.
    ldr     x16, .Lfake
    add     x16, x16, x19
    mov     x0, x16
    ldr     x17, .Lcansee
    add     x17, x17, x19
    blr     x17
    b       .Lgot
.Lskip:
    mov     w0, #1
.Lgot:
    ldr     x16, .Llos
    add     x16, x16, x19
    strb    w0, [x16]

    // clear -> stub+8 (target angle), blocked -> stub+12 (native angle).
    ldr     x16, .Langles
    add     x16, x16, x19
    add     x17, x16, #4
    cbz     w0, .Lblocked
    ldr     s0, [x16]
    b       .Ldone
.Lblocked:
    ldr     s0, [x17]
.Ldone:
    ldp     x19, x20, [sp, #0x10]
    ldp     x29, x30, [sp, #0x20]
    add     sp, sp, #0x30
    ret
    .ltorg
.Lretaddr: .quad 0x00000000006888f4
.Ljoypad:  .quad 0x00000000013f4780
.Lenable:  .quad 0x00000000013f4704
.Lspace:   .quad 0x00000000013c0df8
.Lfake:    .quad 0x00000000013f4000
.Lcansee:  .quad 0x0000000000619e2c
.Llos:     .quad 0x00000000013f4700
.Langles:  .quad 0x00000000006888f8
