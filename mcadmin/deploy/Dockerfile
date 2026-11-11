# The panel and the power helper, built on your PC (see deploy.ps1) and copied in: nothing is compiled on the VPS.
# distroless/static has no shell or package manager: only the two programs.
FROM gcr.io/distroless/static-debian12:nonroot
COPY panel /panel
COPY power /power
ENTRYPOINT ["/panel"]
CMD ["serve"]
