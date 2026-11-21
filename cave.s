    .text
    .global cave
    .type cave, %function
cave:
    sub     sp, sp, #0x30
    stp     x29, x30, [sp, #0x20]
    stp     x19, x20, [sp, #0x10]
    // LR = base + 0x6888f4 (return address after the BL in the stub).
    mov     x19, x30
    ldr     x20, .Lretaddr
    sub     x19, x19, x20          // x19 = library base

    // Save incoming Joypad pointer (x0) for the triggerbot.
    ldr     x16, .Ljoypad
    add     x16, x16, x19
    str     x0, [x16]

    // Guard: chipmunk space must be non-null, else skip the query.
    ldr     x16, .Lspace
    add     x16, x16, x19
    ldr     x16, [x16]
    cbz     x16, .Lskip
    ldr     x16, [x16]
    cbz     x16, .Lskip

    // w0 = Enemy::canSeeTarget(fake enemy). 1 = clear, 0 = blocked.
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

    // Select: clear -> stub+8 (target angle), blocked -> stub+12 (base angle).
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
.Lspace:   .quad 0x00000000013c0df8
.Lfake:    .quad 0x00000000013f4000
.Lcansee:  .quad 0x0000000000619e2c
.Llos:     .quad 0x00000000013f4700
.Langles:  .quad 0x00000000006888f8
