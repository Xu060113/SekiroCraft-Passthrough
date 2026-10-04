.text
.globl scTraceFixture
.seh_proc scTraceFixture
scTraceFixture:
    subq $40, %rsp
    .seh_stackalloc 40
    .seh_endprologue
    stmxcsr scTraceBeforeMxcsr(%rip)
    movl scTraceBeforeMxcsr(%rip), %eax
    xorl $8192, %eax
    movl %eax, scTraceChangedMxcsr(%rip)
    xorq %rax, %rax
    stc
    movq $101, %rax
    movq $102, %rcx
    movq $103, %rdx
    movq $104, %r8
    movq $105, %r9
    movq $106, %r10
    movq $107, %r11
    movups scTraceVector(%rip), %xmm0
    movups scTraceVector(%rip), %xmm1
    movups scTraceVector(%rip), %xmm2
    movups scTraceVector(%rip), %xmm3
    movups scTraceVector(%rip), %xmm4
    movups scTraceVector(%rip), %xmm5
    callq scCombatHitTraceEntry
    addq $40, %rsp
    retq
.seh_endproc
.globl scTraceClobber
scTraceClobber:
    xorq %rax, %rax
    xorq %rcx, %rcx
    xorq %rdx, %rdx
    xorq %r8, %r8
    xorq %r9, %r9
    xorq %r10, %r10
    xorq %r11, %r11
    pxor %xmm0, %xmm0
    pxor %xmm1, %xmm1
    pxor %xmm2, %xmm2
    pxor %xmm3, %xmm3
    pxor %xmm4, %xmm4
    pxor %xmm5, %xmm5
    ldmxcsr scTraceChangedMxcsr(%rip)
    retq
.globl scTraceTarget
scTraceTarget:
    pushfq
    popq scTraceObservedFlags(%rip)
    movq %rax, scTraceObservedRegs(%rip)
    movq %rcx, scTraceObservedRegs+8(%rip)
    movq %rdx, scTraceObservedRegs+16(%rip)
    movq %r8, scTraceObservedRegs+24(%rip)
    movq %r9, scTraceObservedRegs+32(%rip)
    movq %r10, scTraceObservedRegs+40(%rip)
    movq %r11, scTraceObservedRegs+48(%rip)
    movups %xmm0, scTraceObservedVectors(%rip)
    movups %xmm1, scTraceObservedVectors+16(%rip)
    movups %xmm2, scTraceObservedVectors+32(%rip)
    movups %xmm3, scTraceObservedVectors+48(%rip)
    movups %xmm4, scTraceObservedVectors+64(%rip)
    movups %xmm5, scTraceObservedVectors+80(%rip)
    stmxcsr scTraceObservedMxcsr(%rip)
    movq $999, %rax
    retq
