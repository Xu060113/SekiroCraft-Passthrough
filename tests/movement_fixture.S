.text
.globl scTestMove
.globl scTestStore
scTestMove:
    pushq %rsi
    subq $32, %rsp
    movups %xmm6, (%rsp)
    movq %rcx, %rsi
    movups (%rdx), %xmm6
    movups (%rdx), %xmm0
    movq $1234, %r8
    xorl %eax, %eax
    cmpl %eax, %eax
scTestStore:
    movaps %xmm6, 128(%rsi)
    jne scTestFailure
    cmpq $1234, %r8
    jne scTestFailure
    movups %xmm0, 144(%rsi)
    movl $1, %eax
    jmp scTestEnd
scTestFailure:
    movl $0, %eax
scTestEnd:
    movups (%rsp), %xmm6
    addq $32, %rsp
    popq %rsi
    retq
.globl scTestClobber
scTestClobber:
    movl $0x40a00000, (%rdx)
    xorps %xmm0, %xmm0
    xorps %xmm1, %xmm1
    xorps %xmm2, %xmm2
    xorps %xmm3, %xmm3
    xorps %xmm4, %xmm4
    xorps %xmm5, %xmm5
    movq $77, %r8
    movq $77, %r9
    movq $77, %r10
    movq $77, %r11
    movq $77, %rax
    movq $77, %rcx
    movq $77, %rdx
    testq %rax, %rax
    retq
