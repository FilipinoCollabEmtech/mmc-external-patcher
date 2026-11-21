    .text
    .global stub
    .type stub, %function
stub:
    nop                    // no cave: BL would clobber LR and loop forever
    adr     x8, .Langle
    ldr     s0, [x8]
    ret
    .balign 4
.Langle:
    .float  0
    nop
