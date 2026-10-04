.text
.globl scCombatGameEntry
.seh_proc scCombatGameEntry
scCombatGameEntry:
    leaq -200(%rsp), %rsp
    .seh_stackalloc 200
    .seh_endprologue
    movq %rax, 32(%rsp)
    movq %rcx, 40(%rsp)
    movq %rdx, 48(%rsp)
    movq %r8, 56(%rsp)
    movq %r9, 64(%rsp)
    movq %r10, 72(%rsp)
    movq %r11, 80(%rsp)
    pushfq
    popq %rax
    movq %rax, 88(%rsp)
    movups %xmm0, 96(%rsp)
    movups %xmm1, 112(%rsp)
    movups %xmm2, 128(%rsp)
    movups %xmm3, 144(%rsp)
    movups %xmm4, 160(%rsp)
    movups %xmm5, 176(%rsp)
    stmxcsr 192(%rsp)
    callq *scCombatGameCallback(%rip)
    movups 96(%rsp), %xmm0
    movups 112(%rsp), %xmm1
    movups 128(%rsp), %xmm2
    movups 144(%rsp), %xmm3
    movups 160(%rsp), %xmm4
    movups 176(%rsp), %xmm5
    ldmxcsr 192(%rsp)
    pushq 88(%rsp)
    popfq
    movq 32(%rsp), %rax
    movq 40(%rsp), %rcx
    movq 48(%rsp), %rdx
    movq 56(%rsp), %r8
    movq 64(%rsp), %r9
    movq 72(%rsp), %r10
    movq 80(%rsp), %r11
    leaq 200(%rsp), %rsp
    jmpq *scCombatGameContinue(%rip)
.seh_endproc
