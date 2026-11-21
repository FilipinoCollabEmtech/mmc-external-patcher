    .text
    .global stub
    .type stub, %function
stub:
    nop                    // placeholder: BL cave
    adr     x8, .Langle
    ldr     s0, [x8]
    ret
    .balign 4
.Langle:
    .float  0
    nop
